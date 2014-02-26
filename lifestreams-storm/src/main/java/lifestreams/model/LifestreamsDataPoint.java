package lifestreams.model;

import lifestreams.bolt.BaseLifestreamsBolt;
import lifestreams.utils.TimeWindow;

import org.joda.time.DateTime;
import org.ohmage.models.OhmageUser;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

public class LifestreamsDataPoint extends StreamRecord{
	static class GeneratorInfo{
		public String getTopologyId() {
			return topologyId;
		}
		public void setTopologyId(String topologyId) {
			this.topologyId = topologyId;
		}
		public String getComponentId() {
			return componentId;
		}
		public void setComponentId(String componentId) {
			this.componentId = componentId;
		}
		String topologyId;
		String componentId;
	}
	boolean isSnapshot;
	GeneratorInfo generator;
	TimeWindow timeWindow;
	
	public TimeWindow getTimeWindow(){
		return timeWindow;
	}
	public boolean isSnapshot() {
		return isSnapshot;
	}
	public void setSnapshot(boolean isSnapshot) {
		this.isSnapshot = isSnapshot;
	}
	@JsonProperty
	public GeneratorInfo getGenerator() {
		return generator;
	}
	@JsonIgnore // ignore this when deserialization
	public void setGenerator(GeneratorInfo generator) {
		this.generator = generator;
	}
	
	
	
	public LifestreamsDataPoint(OhmageUser user, DateTime timestamp, TimeWindow window, BaseLifestreamsBolt generator){
		super(user,timestamp);
		// populate info for generator
		this.generator = new GeneratorInfo();
		this.generator.setComponentId(generator.getComponentId());
		this.generator.setTopologyId(generator.getTopologyId());
		// populate the timewindow this data point cover
		this.timeWindow = window;
		
	}
	
	
}
