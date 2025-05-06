package com.fhirflare.custom;

import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.provider.ServerCapabilityStatementProvider;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.instance.model.api.IBaseConformance;
// NO Spring annotations needed
import jakarta.servlet.http.HttpServletRequest;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Custom CapabilityStatement provider loaded via hapi.fhir.custom-provider-classes.
 * Filters resources based on a whitelist stored in a SQLite database.
 * Enabled/disabled via the FHIRFLARE_CUSTOM_WHITELIST_ENABLED environment variable.
 */
public class ResourceWhitelistCapabilityStatementCustomizer extends ServerCapabilityStatementProvider {

    private static final String DB_URL = "jdbc:sqlite:/app/instance/fhir_ig.db"; // Verify path
    private static final Logger LOGGER = Logger.getLogger(ResourceWhitelistCapabilityStatementCustomizer.class.getName());
    private static final String ENV_VAR_WHITELIST_ENABLED = "FHIRFLARE_CUSTOM_WHITELIST_ENABLED";

    private final boolean whitelistEnabled; // Final as it's set in constructor

    /**
     * Constructor. Reads the environment variable to set the enabled state.
     * HAPI FHIR injects the server instance when loading via custom-provider-classes.
     */
    public ResourceWhitelistCapabilityStatementCustomizer(RestfulServer server) {
        super(server); // Pass the server instance
        String enabledEnv = System.getenv(ENV_VAR_WHITELIST_ENABLED);
        this.whitelistEnabled = "true".equalsIgnoreCase(enabledEnv); // Default false if null or not "true"
        LOGGER.log(Level.INFO, "CONSTRUCTOR: ResourceWhitelistCapabilityStatementCustomizer instance created (via HAPI property). Whitelist Enabled (from Env Var ''{0}'') = {1}",
                   new Object[]{ENV_VAR_WHITELIST_ENABLED, this.whitelistEnabled});
         LOGGER.log(Level.INFO, "CONSTRUCTOR: Server instance provided: {0}", (server != null ? server.getClass().getName() : "null"));
    }

     // --- NO-ARG CONSTRUCTOR - Keep commented unless the above constructor causes instantiation issues ---
//    /**
//     * Alternative No-Arg Constructor (Try if HAPI fails to instantiate with the RestfulServer arg)
//     */
//   public ResourceWhitelistCapabilityStatementCustomizer() {
//        super(null); // Pass null if server instance isn't needed/provided by this mechanism
//        String enabledEnv = System.getenv(ENV_VAR_WHITELIST_ENABLED);
//        this.whitelistEnabled = "true".equalsIgnoreCase(enabledEnv);
//        LOGGER.log(Level.INFO, "CONSTRUCTOR (No-Arg): ResourceWhitelistCapabilityStatementCustomizer instance created (via HAPI property). Whitelist Enabled (from Env Var ''{0}'') = {1}",
//                   new Object[]{ENV_VAR_WHITELIST_ENABLED, this.whitelistEnabled});
//   }


    @Override
    public IBaseConformance getServerConformance(HttpServletRequest theRequest, RequestDetails theRequestDetails) {
        LOGGER.log(Level.INFO, "METHOD ENTRY: getServerConformance called. Request URI: [{0}]. Whitelist Filtering Feature Enabled: [{1}]",
                   new Object[]{theRequest.getRequestURI(), this.whitelistEnabled}); // Use instance variable

        IBaseConformance conformance = super.getServerConformance(theRequest, theRequestDetails);

        if (!this.whitelistEnabled) { // Use instance variable
            LOGGER.info("Whitelist filtering is DISABLED via environment variable. Returning original CapabilityStatement.");
            return conformance;
        }

        LOGGER.info("Whitelist filtering is ENABLED. Proceeding with filtering logic.");

        if (!(conformance instanceof CapabilityStatement)) {
             LOGGER.log(Level.WARNING, "Conformance object is not an R4 CapabilityStatement ({0}). Cannot apply whitelist filtering.", conformance.getClass().getName());
             return conformance;
        }
        CapabilityStatement capabilityStatement = (CapabilityStatement) conformance;
        List<String> whitelist = loadWhitelist();
        LOGGER.log(Level.INFO, "Loaded whitelist from database contains {0} entries: {1}", new Object[]{whitelist.size(), whitelist});
        if (!whitelist.isEmpty()) {
            capabilityStatement.getRest().forEach(rest -> {
                List<CapabilityStatement.CapabilityStatementRestResourceComponent> originalResources = new ArrayList<>(rest.getResource());
                List<CapabilityStatement.CapabilityStatementRestResourceComponent> resourcesToKeep = new ArrayList<>();
                List<String> removedTypes = new ArrayList<>();
                List<String> keptTypes = new ArrayList<>();
                for (CapabilityStatement.CapabilityStatementRestResourceComponent resource : originalResources) {
                    String resourceType = resource.getType();
                    boolean isWhitelisted = whitelist.contains(resourceType);
                    if (isWhitelisted) {
                        resourcesToKeep.add(resource);
                        keptTypes.add(resourceType);
                    } else {
                        removedTypes.add(resourceType);
                    }
                }
                rest.setResource(resourcesToKeep);
                if (!removedTypes.isEmpty()) {
                    LOGGER.log(Level.INFO, "Filtered resources in REST mode [{0}]. Kept [{1}]: {2}. Removed [{3}]: {4}",
                               new Object[]{rest.getMode().toString(), keptTypes.size(), keptTypes, removedTypes.size(), removedTypes});
                } else {
                    LOGGER.log(Level.INFO, "No resources removed in REST mode [{0}]. All present resources were whitelisted [{1}]: {2}",
                               new Object[]{rest.getMode().toString(), keptTypes.size(), keptTypes});
                }
            });
        } else {
            LOGGER.log(Level.WARNING, "Whitelist filtering is ENABLED, but the loaded whitelist is empty. No filtering applied.");
        }
        LOGGER.info("METHOD EXIT: getServerConformance - Returning potentially filtered CapabilityStatement.");
        return capabilityStatement;
    }

    // loadWhitelist method remains the same (using JDBC)
    private List<String> loadWhitelist() {
        List<String> whitelist = new ArrayList<>();
        LOGGER.log(Level.FINE, "Attempting to load whitelist from DB: {0}", DB_URL);
        Connection conn = null; Statement stmt = null; ResultSet rs = null;
        try {
            Class.forName("org.sqlite.JDBC");
            conn = DriverManager.getConnection(DB_URL);
            stmt = conn.createStatement();
            rs = stmt.executeQuery("SELECT resource_type FROM whitelist_config");
            int count = 0;
            while (rs.next()) {
                String resourceType = rs.getString("resource_type");
                if (resourceType != null && !resourceType.trim().isEmpty()) {
                   whitelist.add(resourceType.trim());
                   LOGGER.log(Level.FINEST, "LOADED: Added resource type to whitelist: [{0}]", resourceType.trim());
                   count++;
                } else {
                    LOGGER.log(Level.WARNING, "Found null or empty resource_type in whitelist_config table.");
                }
            }
             LOGGER.log(Level.INFO, "DB Load: Successfully loaded {0} resource types from whitelist table.", count);
        } catch (ClassNotFoundException e) {
             LOGGER.log(Level.SEVERE, "FATAL: SQLite JDBC Driver (org.sqlite.JDBC) not found on classpath! Ensure the JAR is in tomcat/lib.", e);
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "SQL error loading whitelist from database: " + e.getMessage() + " SQLState: " + e.getSQLState(), e);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unexpected error loading whitelist from database: " + e.getMessage(), e);
        } finally {
             try { if (rs != null) rs.close(); LOGGER.log(Level.FINEST, "Closed ResultSet."); } catch (SQLException e) { LOGGER.log(Level.WARNING, "Error closing ResultSet", e); }
             try { if (stmt != null) stmt.close(); LOGGER.log(Level.FINEST, "Closed Statement."); } catch (SQLException e) { LOGGER.log(Level.WARNING, "Error closing Statement", e); }
             try { if (conn != null) conn.close(); LOGGER.log(Level.FINE, "Closed DB Connection."); } catch (SQLException e) { LOGGER.log(Level.WARNING, "Error closing Connection", e); }
        }
        return whitelist;
    }
}