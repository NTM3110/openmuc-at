package org.openmuc.framework.app.simpledemo;

import java.util.List;
import java.util.regex.*;

public class ChannelLayout {

    // Make it public and static so it's accessible and doesn't capture outer instance

	private static final Pattern P = Pattern.compile("^str(\\d+)_cell_qty$");

    public static Integer getIndex(String s) {
        Matcher m = P.matcher(s);
        if (m.matches()) {                 // or m.find() if you don't want anchors
            return Integer.parseInt(m.group(1)); // <- "1" or "12"
        }
        return null; // or throw / OptionalInt, up to you
    }
}

