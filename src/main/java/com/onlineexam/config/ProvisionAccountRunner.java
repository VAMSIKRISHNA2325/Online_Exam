package com.onlineexam.config;

import com.onlineexam.domain.Role;
import com.onlineexam.service.AccountProvisioningService;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("provision & !demo")
public class ProvisionAccountRunner implements ApplicationRunner {
    private final AccountProvisioningService provisioning;
    private final String username;
    private final String displayName;
    private final String role;
    private final String password;

    public ProvisionAccountRunner(AccountProvisioningService provisioning,
                                  @Value("${PROVISION_USERNAME:}") String username,
                                  @Value("${PROVISION_DISPLAY_NAME:}") String displayName,
                                  @Value("${PROVISION_ROLE:}") String role,
                                  @Value("${PROVISION_PASSWORD:}") String password) {
        this.provisioning = provisioning;
        this.username = username;
        this.displayName = displayName;
        this.role = role;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        Role accountRole;
        try {
            accountRole = Role.valueOf(role.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("PROVISION_ROLE must be STUDENT or INSTRUCTOR");
        }
        provisioning.provision(username, displayName, accountRole, password);
        System.out.println("Account created: " + username.trim().toLowerCase(Locale.ROOT)
                + " (" + accountRole + ")");
    }
}
