package dev.dwoodard.voxelpilot.plan;

// A plan the validator refuses. The message is written for both the user and the model:
// on a rejected plan, AiPlanner sends it back to the model so it can repair its own output.
public final class PlanException extends RuntimeException {
    public PlanException(String message) { super(message); }
}
