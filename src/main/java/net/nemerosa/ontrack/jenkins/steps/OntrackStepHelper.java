package net.nemerosa.ontrack.jenkins.steps;

import com.cloudbees.workflow.flownode.FlowNodeUtil;
import com.cloudbees.workflow.rest.external.StatusExt;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import net.nemerosa.ontrack.jenkins.OntrackPluginSupport;
import org.jenkinsci.plugins.workflow.actions.TimingAction;
import org.jenkinsci.plugins.workflow.cps.nodes.StepStartNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class OntrackStepHelper {

    public static @Nullable
    String toValidationRunStatus(@Nullable Result result) {
        if (result == null || result.equals(Result.SUCCESS)) {
            return "PASSED";
        } else if (result.equals(Result.UNSTABLE)) {
            return "WARNING";
        } else if (result.equals(Result.FAILURE)) {
            return "FAILED";
        } else if (result.equals(Result.ABORTED)) {
            return "INTERRUPTED";
        } else {
            return null;
        }
    }

    public static String getValidationRunStatusFromStage(StepContext context) throws IOException, InterruptedException {
        // Gets the current node
        FlowNode flowNode = context.get(FlowNode.class);
        if (flowNode != null) {
            // Gets the current stage
            FlowNode stage = getStage(flowNode);
            // If there is a stage, gets its status and converts it
            if (stage != null) {
                Result result = getStageStatusAsResult(stage);
                return toValidationRunStatus(result);
            }
        }
        // No node not stage, takes the current build status as a fallback
        return getValidationRunStatusFromRun(context);
    }

    public static String getValidationRunStatusFromRun(StepContext context) throws IOException, InterruptedException {
        Run run = context.get(Run.class);
        if (run != null) {
            Result result = run.getResult();
            return toValidationRunStatus(result);
        } else {
            throw new IllegalStateException("Cannot get any status when not running in a build.");
        }
    }

    public static @Nullable
    Map<String, ?> getRunInfo(StepContext context, TaskListener taskListener) throws IOException, InterruptedException {
        // Gets the associated run
        Run run = context.get(Run.class);
        if (run == null) {
            // No run? Getting away from there...
            return null;
        }
        // Base run info
        Map<String, Object> runInfo = OntrackPluginSupport.getRunInfo(run, taskListener);
        // Adaptation
        adaptRunInfo(context, runInfo);
        // Run info if not empty
        if (runInfo.isEmpty()) {
            return null;
        } else {
            return runInfo;
        }
    }

    public static void adaptRunInfo(StepContext context, Map<String, Object> runInfo) {
        // Gets the (current) duration of the stage
        FlowNode flowNode = null;
        try {
            flowNode = context.get(FlowNode.class);
        } catch (IOException | InterruptedException ignored) {
        }
        if (flowNode != null) {
            Long durationSeconds = getTiming(flowNode);
            if (durationSeconds != null) {
                runInfo.put("runTime", durationSeconds);
            }
        }
    }

    private static Long getTiming(FlowNode node) {
        if (node instanceof StepStartNode) {
            StepStartNode stepNode = (StepStartNode) node;
            StepDescriptor stepDescriptor = stepNode.getDescriptor();
            if (stepDescriptor != null) {
                String stepDescriptorId = stepDescriptor.getId();
                if ("org.jenkinsci.plugins.workflow.support.steps.StageStep".equals(stepDescriptorId)) {
                    TimingAction timingAction = node.getAction(TimingAction.class);
                    if (timingAction != null) {
                        long startTime = timingAction.getStartTime();
                        return (System.currentTimeMillis() - startTime) / 1000;
                    }
                }
            }
        }
        return getTiming(node.getParents());
    }

    private static Long getTiming(List<FlowNode> nodes) {
        for (FlowNode node : nodes) {
            Long durationSeconds = getTiming(node);
            if (durationSeconds != null) {
                return durationSeconds;
            }
        }
        return null;
    }

    private static Result getStageStatusAsResult(FlowNode node) {
        Result current = toResult(FlowNodeUtil.getStatus(node));
        List<FlowNode> otherNodes = FlowNodeUtil.getStageNodes(node);
        for (FlowNode otherNode : otherNodes) {
            Result other = toResult(FlowNodeUtil.getStatus(otherNode));
            if (current == null || (other != null && other.isWorseThan(current))) {
                current = other;
            }
        }
        return current;
    }

    private static @Nullable
    Result toResult(StatusExt statusExt) {
        if (statusExt == null) {
            return null;
        } else {
            switch (statusExt) {
                case NOT_EXECUTED:
                    return null;
                case ABORTED:
                    return Result.ABORTED;
                case SUCCESS:
                    return Result.SUCCESS;
                case IN_PROGRESS:
                    // Still running - means still OK
                    return Result.SUCCESS;
                case PAUSED_PENDING_INPUT:
                    return null;
                case FAILED:
                    return Result.FAILURE;
                case UNSTABLE:
                    return Result.UNSTABLE;
                default:
                    return null;
            }
        }
    }

    private static @Nullable
    FlowNode getStage(FlowNode node) {
        if (node instanceof StepStartNode) {
            StepStartNode stepNode = (StepStartNode) node;
            StepDescriptor stepDescriptor = stepNode.getDescriptor();
            if (stepDescriptor != null) {
                String stepDescriptorId = stepDescriptor.getId();
                if ("org.jenkinsci.plugins.workflow.support.steps.StageStep".equals(stepDescriptorId)) {
                    return stepNode;
                }
            }
        }
        return getStage(node.getParents());
    }

    private static FlowNode getStage(List<FlowNode> nodes) {
        for (FlowNode node : nodes) {
            FlowNode stage = getStage(node);
            if (stage != null) {
                return stage;
            }
        }
        return null;
    }
}