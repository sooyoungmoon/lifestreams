package lifestreams.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.joda.time.DateTime;
import org.joda.time.Interval;


public class ActivityInstance{
	public static class TrackPoint{
		public TrackPoint(double lat, double lng, DateTime time) {
			super();
			this.lat = lat;
			this.lng = lng;
			this.time = time;
		}
		public double getLat() {
			return lat;
		}
		public void setLat(double lat) {
			this.lat = lat;
		}
		public double getLng() {
			return lng;
		}
		public void setLng(double lng) {
			this.lng = lng;
		}
		public DateTime getTime() {
			return time;
		}
		public void setTime(DateTime time) {
			this.time = time;
		}
		double lat, lng;
		DateTime time;
	}
	public Set<MobilityState> getTypes() {
		return types;
	}
	public void setTypes(Set<MobilityState> types) {
		this.types = types;
	}
	public DateTime getEndTime() {
		return endTime;
	}
	public void setEndTime(DateTime endTime) {
		this.endTime = endTime;
	}
	public DateTime getStartTime() {
		return startTime;
	}
	public void setStartTime(DateTime startTime) {
		this.startTime = startTime;
	}
	public double getDuration() {
		return new Interval(this.getStartTime(), this.getEndTime()).toDurationMillis() / 1000;
	}
	public double getDistance() {
		return distance;
	}
	public void setDistance(double distance) {
		this.distance = distance;
	}
	public List<TrackPoint> getTrackingPoints() {
		return trackPoints;
	}
	public void setTrackingPoints(List<TrackPoint> trackPoints) {
		this.trackPoints = trackPoints;
	}
	Set<MobilityState> types = new HashSet<MobilityState>();
	DateTime endTime;
	DateTime startTime;
	double duration;
	double distance;
	List<TrackPoint> trackPoints = new ArrayList<TrackPoint>();
}
