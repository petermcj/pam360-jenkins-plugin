package io.jenkins.plugins.pam360;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import hudson.Extension;
import hudson.model.Item;
import hudson.model.Run;
import hudson.security.ACL;
import hudson.util.ListBoxModel;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import java.util.Set;

/**
 * Declarative pipeline step that fetches a credential from PAM360 and exposes it
 * as an environment variable within the enclosed block.
 *
 * <pre>{@code
 * withPAM360(credentialId: 'pam360-api-token',
 *            resourceId:   '42',
 *            accountId:    '7',
 *            variable:     'DB_PASSWORD') {
 *     sh 'echo $DB_PASSWORD'
 * }
 * }</pre>
 *
 * {@code credentialId} points to a Jenkins <em>Secret text</em> credential that holds
 * the PAM360 AUTHTOKEN for this particular resource fetch.  The PAM360 base URL is
 * read from the global Jenkins configuration, or can be overridden per-step with
 * {@code apiUrl}.
 */
public class PAM360Step extends Step {

    private final String credentialId;
    private final String resourceId;
    private final String accountId;
    private final String variable;

    /** Optional per-step override of the globally configured PAM360 URL. */
    private String apiUrl;

    @DataBoundConstructor
    public PAM360Step(String credentialId, String resourceId, String accountId, String variable) {
        this.credentialId = credentialId;
        this.resourceId = resourceId;
        this.accountId = accountId;
        this.variable = variable;
    }

    public String getCredentialId() { return credentialId; }
    public String getResourceId()   { return resourceId; }
    public String getAccountId()    { return accountId; }
    public String getVariable()     { return variable; }
    public String getApiUrl()       { return apiUrl; }

    @DataBoundSetter
    public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new PAM360StepExecution(this, context);
    }

    @Extension
    public static final class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "withPAM360";
        }

        @Override
        public String getDisplayName() {
            return "Retrieve credential from PAM360";
        }

        @Override
        public boolean takesImplicitBlockArgument() {
            return true;
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(Run.class);
        }

        public ListBoxModel doFillCredentialIdItems(
                @AncestorInPath Item item,
                @QueryParameter String credentialId) {

            if (item == null || !item.hasPermission(Item.CONFIGURE)) {
                return new StandardListBoxModel().includeCurrentValue(credentialId);
            }
            return new StandardListBoxModel()
                    .includeEmptyValue()
                    .includeAs(ACL.SYSTEM, item, StringCredentials.class)
                    .includeCurrentValue(credentialId);
        }
    }
}
