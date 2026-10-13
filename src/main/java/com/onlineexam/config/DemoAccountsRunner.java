package com.onlineexam.config;

import com.onlineexam.service.AccountProvisioningService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("demo")
public class DemoAccountsRunner implements ApplicationRunner {
    private final AccountProvisioningService provisioning;
    private final String password;

    public DemoAccountsRunner(AccountProvisioningService provisioning,
                              @Value("${DEMO_PASSWORD:}") String password) {
        this.provisioning = provisioning;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        provisioning.seedDemoAccounts(password);
    }
}
