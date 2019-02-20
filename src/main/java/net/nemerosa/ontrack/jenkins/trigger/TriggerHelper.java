package net.nemerosa.ontrack.jenkins.trigger;

import com.google.common.collect.ImmutableMap;
import hudson.model.*;
import hudson.util.LogTaskListener;
import jenkins.model.ParameterizedJobMixIn;
import net.nemerosa.ontrack.dsl.Branch;
import net.nemerosa.ontrack.dsl.Build;
import net.nemerosa.ontrack.dsl.Ontrack;
import net.nemerosa.ontrack.jenkins.dsl.OntrackDSLConnector;
import org.apache.commons.lang.StringUtils;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class TriggerHelper {

    private static final Logger LOGGER = Logger.getLogger(TriggerHelper.class.getName());
    private static final Level LOG_LEVEL = Level.FINE;

    public static void evaluate(Job job, List<TriggerDefinition> triggers) {
        // Evaluates the condition for each trigger
        List<TriggerResult> results = triggers.stream()
                .map(trigger -> getTriggerResult(job, trigger))
                .collect(Collectors.toList());

        // Final say
        boolean firing;

        // If at least one trigger says "fire"
        boolean oneFiring = results.stream().anyMatch(TriggerResult::isFiring);
        if (oneFiring) {
            // We have to check that all trigger results have their parameter value filled in
            firing = results.stream().allMatch(result -> StringUtils.isNotBlank(result.getNewValue()));
        } else {
            // No trigger mentions that we should fire anything...
            firing = false;
        }

        // Firing
        // Summary
        if (!firing) {
            LOGGER.log(LOG_LEVEL, String.format("[ontrack][trigger][%s] For one of the reasons mentioned above, not firing", job.getFullName()));
        } else {
            LOGGER.log(LOG_LEVEL, String.format("[ontrack][trigger][%s] Firing", job.getFullName()));
            // Collecting the parameters
            List<ParameterValue> parameters = results.stream()
                    .map(result -> new StringParameterValue(result.getName(), result.getNewValue()))
                    .collect(Collectors.toList());
            // Scheduling
            ParameterizedJobMixIn.scheduleBuild2(
                    job,
                    0,
                    new CauseAction(new OntrackTriggerCause()),
                    new ParametersAction(parameters)
            );
        }
    }

    private static TriggerResult getTriggerResult(Job job, TriggerDefinition trigger) {

        // Ontrack accessor
        Ontrack ontrack = OntrackDSLConnector.createOntrackConnector(System.out);

        // Gets the Ontrack branch
        Branch ontrackBranch = ontrack.branch(trigger.getProject(), trigger.getBranch());

        // Gets the last builds
        List<Build> ontrackBuilds;
        if (StringUtils.isBlank(trigger.getPromotion())) {
            ontrackBuilds = ontrackBranch.standardFilter(Collections.singletonMap(
                    "count", 1
            ));
        }
        // Gets the last promoted build
        else if ("*".equals(trigger.getPromotion())) {
            ontrackBuilds = ontrackBranch.getLastPromotedBuilds();
        }
        // Gets the last build with promotion
        else {
            ontrackBuilds = ontrackBranch.standardFilter(ImmutableMap.of(
                    "count", 1,
                    "withPromotionLevel", trigger.getPromotion()
            ));
        }

        // Nothing eligible
        if (ontrackBuilds.isEmpty()) {
            LOGGER.log(LOG_LEVEL, String.format("[ontrack][trigger][%s] No build eligible", job.getFullName()));
            return trigger.noResult();
        }

        // Gets the last version
        String newValue = ontrackBuilds.get(0).getName();
        LOGGER.log(LOG_LEVEL, String.format("[ontrack][trigger][%s] Last available build: %s", job.getFullName(), newValue));

        // Version parameter name
        String parameterName = trigger.getParameterName();

        // Gets any previous build
        Run lastBuild = job.getLastBuild();
        if (lastBuild != null) {
            Result result = lastBuild.getResult();
            Result minimum = Result.fromString(trigger.getMinimumResult());
            if (result == null || (result.isWorseThan(minimum) && result.isCompleteBuild())) {
                LOGGER.log(LOG_LEVEL, String.format("[ontrack][trigger][%s] Last build was failed or unsuccessful", job.getFullName()));
                return trigger.noPrevious(newValue);
            } else {
                // Log listener
                TaskListener taskListener = new LogTaskListener(
                        LOGGER,
                        Level.FINER
                );
                // Gets the last build name
                try {
                    String previousValue = lastBuild.getEnvironment(taskListener).get(parameterName, null);
                    LOGGER.log(LOG_LEVEL, String.format("[ontrack][trigger][%s] Version for last build: %s", job.getFullName(), previousValue));
                    // Result
                    return trigger.withPrevious(previousValue, newValue);
                } catch (IOException | InterruptedException e) {
                    throw new RuntimeException(
                            String.format("[ontrack][trigger][%s] Could not compute the trigger condition because %s environment variable could not be accessed", job.getFullName(), parameterName),
                            e
                    );
                }
            }
        } else {
            LOGGER.log(LOG_LEVEL, String.format("[ontrack][trigger][%s] No previous build, firing", job.getFullName()));
            return trigger.noPrevious(newValue);
        }
    }

}
