package org.openmuc.framework.app.simpledemo;

public class Dimension {
	public final int stringCount;
	public final int cellCount;
	
	public Dimension(int stringCount, int cellCount) {
	    this.stringCount = stringCount;
	    this.cellCount = cellCount;
	}
	
	@Override
	public String toString() {
	    return "Dimensions{strings=" + stringCount + ", cells=" + cellCount + "}";
	}

}
