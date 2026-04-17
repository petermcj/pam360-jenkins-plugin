package io.jenkins.plugins.pam360;

import hudson.Extension;
import jenkins.model.GlobalConfiguration;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * Global Jenkins configuration for PAM360.
 *
 * Stores the default PAM360 API base URL (e.g. https://pam360.example.com).
 * Individual {@code withPAM360} steps may override this with their own {@code apiUrl} parameter.
 */
@Extension
public class PAM360GlobalConfiguration extends GlobalConfiguration {

    private String apiUrl;

    public static PAM360GlobalConfiguration get() {
        return GlobalConfiguration.all().get(PAM360GlobalConfiguration.class);
    }

    public PAM360GlobalConfiguration() {
        load();
    }

    public String getApiUrl() {
        return apiUrl;
    }

    @DataBoundSetter
    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
        save();
    }

    @Override
    public String getDisplayName() {
        return "PAM360 Configuration";
    }
}
