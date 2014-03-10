package lifestreams.tasks.mobility;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

import lifestreams.bolts.TimeWindow;
import lifestreams.bolts.TimeWindowBolt;
import lifestreams.models.MobilityState;
import lifestreams.models.StreamRecord;
import lifestreams.models.data.ActivityInstance;
import lifestreams.models.data.ActivitySummaryData;
import lifestreams.models.data.IMobilityData;
import lifestreams.tasks.SimpleTask;
import lifestreams.utils.ActivityInstanceAccumulator;

import org.joda.time.DateTime;
import org.ohmage.models.OhmageUser;

/**
 * @author changun This task generates a activity summary (see
 *         ActivitySummaryData) from the Mobility data points in a time window
 *         (e.g. daily).
 * 
 */
public class MobilityActivitySummarizer extends SimpleTask<IMobilityData> {

	private static final Double LONGEST_SAMPLING_PERIOD = 5.5 * 60 * 1000; // in millisec


	EnumMap<MobilityState, Double> activityTimeAccumulator;
	ActivityInstanceAccumulator activityInstanceAccumulator;
	List<ActivityInstance> activityInstances;
	StreamRecord<IMobilityData> last_dp;
	
	public void init(OhmageUser user, TimeWindowBolt bolt) {
		super.init(user, bolt);
		initAccumulators();
	}
	private void initAccumulators() {
		activityTimeAccumulator = new EnumMap<MobilityState, Double> (MobilityState.class);
		for (MobilityState mState : MobilityState.values()) {
			activityTimeAccumulator.put(mState, 0.0);
		}
		activityInstances = new ArrayList<ActivityInstance>();
		activityInstanceAccumulator = new ActivityInstanceAccumulator ();
		last_dp = null;
	}
	
	private void accumulateActivityTimes(StreamRecord<IMobilityData> cur_dp) {
		if (last_dp != null) {
			MobilityState curState = cur_dp.d().getMode();
			MobilityState prevState = last_dp.d().getMode();
			DateTime dt = cur_dp.getTimestamp();
			// get duration in seconds
			long duration = (dt.getMillis() - last_dp.getTimestamp().getMillis()) / 1000;
			// only accumulate the samples with sufficient frequency
			if (duration < LONGEST_SAMPLING_PERIOD) {
				Double halfPeriod = duration / 2.0;
				// both states the sandwich the sampled period is responsible for one half of the duration
				activityTimeAccumulator.put(prevState, activityTimeAccumulator.get(prevState) + halfPeriod);
				activityTimeAccumulator.put(curState, activityTimeAccumulator.get(curState)	+ halfPeriod);
			}
		}
	}
	private void accumulateActivityInstance(StreamRecord<IMobilityData> cur_dp) {
		if (cur_dp.d().getMode().isActive()) { // if the current state is active
			// add this point to the accumulator
			activityInstanceAccumulator.addDataPoint(cur_dp);
		} else if (last_dp != null && last_dp.d().getMode().isActive()) { 
			// if the current state is not active, but the last state is active
			// then this is the end of a activity instance
			
			// get the accumulated activity instance til the last data point
			ActivityInstance instance = activityInstanceAccumulator.getInstance();
			// add that to the activity instances array
			activityInstances.add(instance);
			// restart the accumulator
			activityInstanceAccumulator = new ActivityInstanceAccumulator();
		}

	}

	private void updateSummary(StreamRecord<IMobilityData> cur_dp) {
		/* Task 1. accumulate total time for the type of activity */
		accumulateActivityTimes(cur_dp);

		/* Task 2. accumulate the activity instances */

		// an activity instance is composed of continuous active data points
		// an accumulator will compute the statisitcs (e.g. duration, distance) for an instance
		accumulateActivityInstance(cur_dp);
	}

	@Override
	public void executeDataPoint(StreamRecord<IMobilityData> dp, TimeWindow window) {
		if (last_dp == null	|| dp.getTimestamp().isAfter(last_dp.getTimestamp())){
			// make sure we does not go back in time, then update the summaries
			updateSummary(dp);
		}
		// update the last record
		last_dp = dp;
	}

	private StreamRecord<ActivitySummaryData> computeSummaryDataPoint(TimeWindow window) {
		// check if there is an activity instance being accumulated
		if (activityInstanceAccumulator.isInitialized()) {
			// get the accumulated activity instance
			ActivityInstance instance = activityInstanceAccumulator.getInstance();
			// add that to the instance array
			this.activityInstances.add(instance);
		}
		double totalActiveTime =  
				activityTimeAccumulator.get(MobilityState.WALK)
				+  activityTimeAccumulator.get(MobilityState.RUN)
				+  activityTimeAccumulator.get(MobilityState.CYCLING);

		double totalSedentaryTime =  
				activityTimeAccumulator.get(MobilityState.DRIVE)
				+  activityTimeAccumulator.get(MobilityState.STILL);

		double totalTime = totalActiveTime + totalSedentaryTime;
		double totalTransportationTime =  
				activityTimeAccumulator.get(MobilityState.DRIVE);

		ActivitySummaryData data = new ActivitySummaryData(window, this)
				.setTotalActiveTime(totalActiveTime)
				.setTotalSedentaryTime(totalSedentaryTime)
				.setTotalTime(totalTime)
				.setTotalTransportationTime(totalTransportationTime)
				.setActivityInstances(activityInstances);
		
		StreamRecord<ActivitySummaryData> outputRecord =
				new StreamRecord<ActivitySummaryData>(getUser(), window.getLastInstant());
		
		outputRecord.setData(data);
		return outputRecord;
	}


	@Override
	public void finishWindow(TimeWindow window) {
		// emit the summary
		this.emit(computeSummaryDataPoint(window));
		// re-initialize the accumulators
		initAccumulators();
		
	}

	@Override
	public void snapshotWindow(TimeWindow window) {
		this.emitSnapshot(computeSummaryDataPoint(window));
		
	}

}