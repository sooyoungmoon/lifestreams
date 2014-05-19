package org.ohmage.lifestreams.tasks.mobility;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import org.joda.time.DateTime;
import org.joda.time.Days;
import org.joda.time.base.BaseSingleFieldPeriod;
import org.ohmage.lifestreams.bolts.LifestreamsBolt;
import org.ohmage.lifestreams.models.MobilityState;
import org.ohmage.lifestreams.models.StreamRecord;
import org.ohmage.lifestreams.models.data.MobilityData;
import org.ohmage.lifestreams.models.data.RectifiedMobilityData;
import org.ohmage.lifestreams.tasks.SimpleTimeWindowTask;
import org.ohmage.lifestreams.tasks.TimeWindow;
import org.ohmage.models.OhmageUser;
import org.springframework.stereotype.Component;

import be.ac.ulg.montefiore.run.jahmm.Hmm;
import be.ac.ulg.montefiore.run.jahmm.ObservationDiscrete;
import be.ac.ulg.montefiore.run.jahmm.OpdfDiscrete;
import be.ac.ulg.montefiore.run.jahmm.OpdfDiscreteFactory;

import com.bbn.openmap.geo.Geo;
import com.javadocmd.simplelatlng.LatLng;
import com.javadocmd.simplelatlng.LatLngTool;
import com.javadocmd.simplelatlng.util.LengthUnit;

/**
 * @author changun This task uses a Hidden Markov Chain model to correct the
 *         possible errors in the Mobility classification. The process is
 *         required as the Mobility classification is subject to short term
 *         signal variations (for example, variation in ambient Wi-Fi signals,)
 *         and sometimes mis-classify the mobility states. We correct these
 *         possible errors by considering a long series of data points in a
 *         whole (e.g. one day's data) and incorporating the common error
 *         patterns of Mobility classifier in the HMM model. For example,
 *         Mobility classifier tend to mis-classify Still, Walking, or Running
 *         as Drive, or Drive as Still. The HMM modle is able to correct those
 *         errors.
 */
@Component
public class HMMMobilityRectifier extends SimpleTimeWindowTask<MobilityData> {
	

	private static final int DRIVE_VERIFICATION_TIMEFRAME_SIZE = 10 * 60; // in seconds
	private static final int MAXIMUN_ALLOWABLE_SAMPLING_INTERVAL =  3 * 60; // in seconds
	private static final int MINIMUN_SAMPLING_INTERVAL =  30; // in seconds
	transient Hmm<ObservationDiscrete<MobilityState>> hmmModel;
	List<StreamRecord<MobilityData>> data = new ArrayList<StreamRecord<MobilityData>>(100);

	@Override
	public void init() {
		super.init();
		this.hmmModel = createHmmModel();
	}
	@Override
	public void recover(){
		super.recover();
		init();
	}
	int consectiveStill = 0; 
	@Override
	public void executeDataPoint(StreamRecord<MobilityData> dp,	TimeWindow window) {
		if(dp.getData().getMode() == MobilityState.UNKNOWN){
			return;
		}
		if(data.size() > 0){
			DateTime lastTime = data.get(data.size()-1).getTimestamp();
			MobilityState lastState = data.get(data.size()-1).getData().getMode();
			if(lastTime.plusSeconds(MINIMUN_SAMPLING_INTERVAL).isAfter(dp.getTimestamp())){
				// skip too frequent samples
				return;
			}
			else if(lastTime.plusSeconds(MAXIMUN_ALLOWABLE_SAMPLING_INTERVAL).isBefore(dp.getTimestamp())){
				
				// if the sampling gap is too large, perform the rectification on the previous samples
				correctMobilityStates(window, data);
				data.clear();
				checkpoint(lastTime);
				consectiveStill = 0;
			}
			else if(dp.getData().getMode() == MobilityState.STILL && consectiveStill >= 60){
				correctMobilityStates(window, data);
				data.clear();
				checkpoint(lastTime);
				consectiveStill = 0;
			}
		}

		data.add(dp);
		if(dp.getData().getMode() == MobilityState.STILL){
			consectiveStill ++;
		}else{
			consectiveStill = 0;
		}
	}
	private void emitNewRecord(StreamRecord<MobilityData> dp, MobilityState state, TimeWindow window){
		RectifiedMobilityData rectifiedDp = new RectifiedMobilityData(window, this).setMode(state);
		this.createRecord()
		.setData(rectifiedDp)
		.setLocation(dp.getLocation())
		.setTimestamp(dp.getTimestamp())
		.emit();
	}
	public void correctMobilityStates(TimeWindow window, List<StreamRecord<MobilityData>> data){
		
		
		if(data.size() < 20){
			for(StreamRecord<MobilityData> rec: data){
				emitNewRecord(rec, rec.getData().getMode(), window);
			}
			return;
		}
		
		
		// first, correct those DRIVE states whose max displacement in the next and previous 10 minutes is less than 1KM to be STILL or WALK
		for (StreamRecord<MobilityData> dp : data) {
			if(dp.d().getMode() == MobilityState.DRIVE && dp.getLocation() != null){
				LatLng curLocation = dp.getLocation().getCoordinates();
				Double largestDisplacement = 0.0;
				HashSet<MobilityState> modes = new HashSet<MobilityState> ();
				for (StreamRecord<MobilityData> otherDP : data) {
					if(otherDP.getLocation() != null 
					   && Math.abs(otherDP.getTimestamp().getMillis() - dp.getTimestamp().getMillis()) < DRIVE_VERIFICATION_TIMEFRAME_SIZE){
						modes.add(otherDP.d().getMode());
						// compute displacement
						LatLng otherLocation = otherDP.getLocation().getCoordinates();
						largestDisplacement = Math.max(largestDisplacement , 
								                       LatLngTool.distance(curLocation, otherLocation, LengthUnit.KILOMETER));
					}
				}
				if(largestDisplacement < 1){
					// the point if not a DRIVE state. Set it to be WALK if there is WALK state in the surrounding timeframe,
					// otherwise, set it as STILL.
					dp.d().setMode(modes.contains(MobilityState.WALK) ? MobilityState.WALK : MobilityState.STILL);
				}
			}
		}
		// create a list of observations (i.e. mobility states) for HMM
		List<ObservationDiscrete<MobilityState>> observations = 
				new ArrayList<ObservationDiscrete<MobilityState>>();
		for (StreamRecord<MobilityData> dp : data) {
			MobilityData mdp = dp.d();
			observations.add(new ObservationDiscrete<MobilityState>(mdp.getMode()));
		}
		// compute the most likely state given the HMM model
		int[] inferredStates = hmmModel.mostLikelyStateSequence(observations);

		// emit the data with the new states
		for (int i = 0; i < inferredStates.length; i++) {
			MobilityState curState = MobilityState.values()[inferredStates[i]];
			// create a new Mobility data point
			emitNewRecord(data.get(i), curState, window);
		}
	}
	@Override
	public void finishWindow(TimeWindow window) {

	}

	public static Hmm<ObservationDiscrete<MobilityState>> createHmmModel() {
		OpdfDiscreteFactory<MobilityState> factory = new OpdfDiscreteFactory<MobilityState>(
				MobilityState.class);
		Hmm<ObservationDiscrete<MobilityState>> hmm = new Hmm<ObservationDiscrete<MobilityState>>(
				MobilityState.values().length, factory);
		// Assume we will never have CYCLING and UNKNOWN state
		for (MobilityState state : MobilityState.values()) {
			if (state.equals(MobilityState.CYCLING) || state.equals(MobilityState.UNKNOWN)) {
				hmm.setPi(state.ordinal(), 0);
			} else {
				hmm.setPi(state.ordinal(),
						1.0 / (MobilityState.values().length - 2));
			}
		}

		hmm.setOpdf(MobilityState.STILL.ordinal(),
				new OpdfDiscrete<MobilityState>(MobilityState.class,
						new double[] { 0.70, 0.005, 0.005, 0.29, 0, 0 }));
		hmm.setOpdf(MobilityState.RUN.ordinal(),
				new OpdfDiscrete<MobilityState>(MobilityState.class,
						new double[] { 0.20, 0.50, 0.10, 0.20, 0 , 0}));
		hmm.setOpdf(MobilityState.WALK.ordinal(),
				new OpdfDiscrete<MobilityState>(MobilityState.class,
						new double[] { 0.20, 0.10, 0.50, 0.20, 0, 0 }));
		hmm.setOpdf(MobilityState.DRIVE.ordinal(),
				new OpdfDiscrete<MobilityState>(MobilityState.class,
						new double[] { 0.28, 0.01, 0.01, 0.70, 0, 0 }));

		hmm.setAij(MobilityState.STILL.ordinal(),
				MobilityState.STILL.ordinal(), 0.70);
		hmm.setAij(MobilityState.STILL.ordinal(), MobilityState.RUN.ordinal(),
				0.14);
		hmm.setAij(MobilityState.STILL.ordinal(), MobilityState.WALK.ordinal(),
				0.15);
		hmm.setAij(MobilityState.STILL.ordinal(),
				MobilityState.DRIVE.ordinal(), 0.01);
		hmm.setAij(MobilityState.STILL.ordinal(),
				MobilityState.CYCLING.ordinal(), 0.00);
		hmm.setAij(MobilityState.STILL.ordinal(),
				MobilityState.UNKNOWN.ordinal(), 0.00);

		hmm.setAij(MobilityState.RUN.ordinal(), MobilityState.STILL.ordinal(),
				0.10);
		hmm.setAij(MobilityState.RUN.ordinal(), MobilityState.RUN.ordinal(),
				0.49);
		hmm.setAij(MobilityState.RUN.ordinal(), MobilityState.WALK.ordinal(),
				0.40);
		hmm.setAij(MobilityState.RUN.ordinal(), MobilityState.DRIVE.ordinal(),
				0.01);
		hmm.setAij(MobilityState.RUN.ordinal(),
				MobilityState.CYCLING.ordinal(), 0.00);
		hmm.setAij(MobilityState.RUN.ordinal(),
				MobilityState.UNKNOWN.ordinal(), 0.00);
		
		hmm.setAij(MobilityState.WALK.ordinal(), MobilityState.STILL.ordinal(),
				0.10);
		hmm.setAij(MobilityState.WALK.ordinal(), MobilityState.RUN.ordinal(),
				0.40);
		hmm.setAij(MobilityState.WALK.ordinal(), MobilityState.WALK.ordinal(),
				0.49);
		hmm.setAij(MobilityState.WALK.ordinal(), MobilityState.DRIVE.ordinal(),
				0.01);
		hmm.setAij(MobilityState.WALK.ordinal(),
				MobilityState.CYCLING.ordinal(), 0.00);
		hmm.setAij(MobilityState.WALK.ordinal(),
				MobilityState.UNKNOWN.ordinal(), 0.00);

		hmm.setAij(MobilityState.DRIVE.ordinal(),
				MobilityState.STILL.ordinal(), 0.09);
		hmm.setAij(MobilityState.DRIVE.ordinal(), MobilityState.RUN.ordinal(),
				0.01);
		hmm.setAij(MobilityState.DRIVE.ordinal(), MobilityState.WALK.ordinal(),
				0.20);
		hmm.setAij(MobilityState.DRIVE.ordinal(),
				MobilityState.DRIVE.ordinal(), 0.70);
		hmm.setAij(MobilityState.DRIVE.ordinal(),
				MobilityState.CYCLING.ordinal(), 0.00);
		hmm.setAij(MobilityState.DRIVE.ordinal(),
				MobilityState.UNKNOWN.ordinal(), 0.00);
		return (hmm);
	}
}
