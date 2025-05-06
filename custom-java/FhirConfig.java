package com.fhirflare.custom;

import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.provider.ServerCapabilityStatementProvider;
import com.zaxxer.hikari.HikariDataSource; // Import HikariDataSource specifically if checking for it
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Spring configuration class for custom FHIRFlare beans.
 * Includes explicit DataSource configuration to resolve startup issues in WAR deployment.
 */
@Configuration
public class FhirConfig {

    private static final Logger LOGGER = Logger.getLogger(FhirConfig.class.getName());

    public FhirConfig() {
        LOGGER.log(Level.INFO, "CONSTRUCTOR: FhirConfig instance created by Spring.");
    }

    /**
     * Explicitly defines the DataSource bean for HAPI FHIR's JPA persistence.
     * Reads configuration properties prefixed with 'spring.datasource' from application.yaml.
     * Marked as @Primary to ensure it's the preferred DataSource if others might exist.
     *
     * @return The configured DataSource bean.
     */
    @Bean
    @Primary // Ensures this DataSource is used if multiple candidates exist
    @ConfigurationProperties(prefix = "spring.datasource") // Binds properties like url, username, password, driverClassName
    public DataSource dataSource() {
        LOGGER.log(Level.INFO, "BEAN METHOD: Explicitly creating DataSource bean from spring.datasource properties...");
        try {
            // DataSourceBuilder conveniently creates a DataSource based on standard properties.
            // It will automatically pick a connection pool implementation (e.g., HikariCP)
            // if available on the classpath (spring-boot-starter-jdbc provides this).
            DataSource ds = DataSourceBuilder.create().build();

            // Log details about the created DataSource
            logDataSourceDetails(ds);

            LOGGER.log(Level.INFO, "BEAN METHOD: Successfully created explicit DataSource bean.");
            return ds;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "BEAN METHOD: FAILED to create explicit DataSource bean!", e);
            // Re-throw to ensure Spring context creation fails clearly if DB config is broken
            throw new RuntimeException("Failed to create DataSource bean", e);
        }
    }

    /**
     * Helper method to log details about the created DataSource.
     * Attempts to log the JDBC URL based on HikariCP implementation (most common with starter).
     * Avoids direct compile-time dependency on Tomcat pool classes.
     * @param ds The DataSource to log details for.
     */
    private void logDataSourceDetails(DataSource ds) {
         String url = "N/A (Could not determine specific URL)";
         String dsImplName = ds.getClass().getName(); // Get the actual implementation class name

         // Check if it's HikariCP using the imported class
         // This check requires HikariCP to be the runtime pooling library, which is the default
         // for spring-boot-starter-jdbc in recent versions.
         if (ds instanceof HikariDataSource) {
              HikariDataSource hikariDs = (HikariDataSource) ds;
              url = hikariDs.getJdbcUrl();
              dsImplName = "HikariDataSource"; // Use simpler name for clarity in logs
         }
         // NOTE: We removed the check for org.apache.tomcat.jdbc.pool.DataSource
         // because that class is not available during compile time for this module.

         LOGGER.log(Level.INFO, "DataSource Bean Details - Implementation: [{0}], JDBC URL: [{1}]",
                    new Object[]{dsImplName, url});
    }


    /**
     * Creates and registers the ResourceWhitelistCapabilityStatementCustomizer bean.
     * This bean customizes the server's CapabilityStatement.
     * Depends on the DataSource bean being available.
     *
     * @param server The RestfulServer instance (injected by Spring).
     * @return An instance of the customizer.
     */
    @Bean
    public ServerCapabilityStatementProvider capabilityStatementProvider(RestfulServer server) {
        LOGGER.log(Level.INFO, "BEAN METHOD: capabilityStatementProvider bean creation requested.");
        try {
            // Ensure ResourceWhitelistCapabilityStatementCustomizer exists and is correct
            ResourceWhitelistCapabilityStatementCustomizer customizer = new ResourceWhitelistCapabilityStatementCustomizer(server);
            LOGGER.log(Level.INFO, "BEAN METHOD: Successfully instantiated ResourceWhitelistCapabilityStatementCustomizer.");
            return customizer;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "BEAN METHOD: FAILED to create ResourceWhitelistCapabilityStatementCustomizer bean!", e);
            throw new RuntimeException("Failed to create ResourceWhitelistCapabilityStatementCustomizer bean", e);
        }
    }
}