package com.example.store;

import org.apache.catalina.util.ServerInfo;
import org.junit.jupiter.api.Test;

import java.lang.module.ModuleDescriptor.Version;

import static org.assertj.core.api.Assertions.assertThat;

class DependencyOverridesTest {

    @Test
    void jacksonCarriesTheFixesForItsHighFindings() {
        assertThat(Version.parse(com.fasterxml.jackson.core.json.PackageVersion.VERSION.toString()))
                .isGreaterThanOrEqualTo(Version.parse("2.21.8"));
        assertThat(Version.parse(com.fasterxml.jackson.databind.cfg.PackageVersion.VERSION.toString()))
                .isGreaterThanOrEqualTo(Version.parse("2.21.8"));
    }

    @Test
    void postgresqlDriverCarriesTheFixForItsHighFinding() throws ClassNotFoundException {
        String version = Class.forName("org.postgresql.Driver").getPackage().getImplementationVersion();

        assertThat(Version.parse(version)).isGreaterThanOrEqualTo(Version.parse("42.7.14"));
    }

    @Test
    void tomcatCarriesTheFixesForItsCriticalFindings() {
        assertThat(Version.parse(ServerInfo.getServerNumber())).isGreaterThanOrEqualTo(Version.parse("10.1.61"));
    }
}
