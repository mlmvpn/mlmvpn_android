package org.scilab.forge.jlatexmath;

public class DepthLimitExceededException extends ParseException {

    private static final long serialVersionUID = 1L;

    public DepthLimitExceededException() {
        super("Maximum formula nesting depth exceeded");
    }
}
