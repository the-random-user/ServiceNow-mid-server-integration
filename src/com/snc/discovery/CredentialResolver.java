/**************************************************************
 * Application:
 * Thycotic SecretServer Integration - ServiceNow
 * Authors:
 * Justin Roberts, Ben Yoder
 * Requirements:
 * Thycotic v10.4,
 * TSS CLI SDK for v10.4,
 * External Credential Storage Plug-in
 *
 *
 * Description:
 * This integration allows access of credentials
 * stored in a Thycotic SecretServer to be used in Discovery,
 * Service Mapping and Orchestration within ServiceNow by
 * utilizing the command line TSS CLI SDK.
 *
 * Qualifier & TSS hard coded to C:\TSS, can be modified below or via env variable
 * qualifier.properties, stored with the CLI tool, options include:
 * SecretServer URL
 * SecretServer Rule Name
 * SecretServer Onboarding Key
 * SecretServer Cache Strategy
 * SecretServer Cache Dump (Minutes)
 **************************************************************/

package com.snc.discovery;

import java.io.*;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.io.IOUtils;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

public class CredentialResolver {

    //environment variable with path to folder of CLI & qualifier.properties file
    private static String TSS_ENV_VAR = "TSS_LOCATION";
    //Required for mapping to ServiceNow
    public static final String ARG_ID = "id";
    public static final String ARG_TYPE = "type";
    public static String VAL_USER = "user";
    public static String VAL_PSWD = "pswd";
    public static String VAL_PASSPHRASE = "passphrase";
    public static String VAL_PKEY = "pkey";
    public static String VAL_AUTHPROTO = "authprotocol";
    public static String VAL_AUTHKEY = "authkey";
    public static String VAL_PRIVPROTO = "privprotocol";
    public static String VAL_PRIVKEY = "privkey";
    public Properties fProps;
    private String sdkFilePath;

    /**
     * Default constructor
     */
    public CredentialResolver() {
    }

    /**
     * Gets the Thycotic SDK installation path from an environment variable. If not present returns a default path.
     *
     * @return  folder containing the tss.exe SDK file
     * @since   1.0
     */
    private String getSdkFilePath() {
        String cliFilePath = System.getenv(TSS_ENV_VAR);
        if (cliFilePath == null) {
            cliFilePath = "C:\\TSS\\";
        }
        System.out.println("Using thycotic SDK in folder: " + cliFilePath);
        return cliFilePath;
    }

    /**
     * Loads the qualifier.properties configuration file from the Thycotic SDK folder
     *
     * @return  a parsed Properties object with Secret Server specific settings.
     * @since   1.0
     */
    private Properties getConfigurationProperties(String filename) throws Exception {
        Properties cliProperties = new Properties();
        FileInputStream configFileStream = null;
        try {
            if (!new File(sdkFilePath + filename).isFile()) {
                System.err.println("Can't find " + filename + " path for tss.exe initialization.");
            }
            else {
                configFileStream = new FileInputStream(sdkFilePath + filename);
                cliProperties.load(configFileStream);
            }
        } catch (IOException e) {
            System.err.println("Problem loading cli properties file: " + e);
        }
        finally {
            if(configFileStream != null)
                configFileStream.close();
        }
        return cliProperties;
    }

    /**
     * Calls the Thycotic SDK to execute a command and return the output
     *
     * @param command   the tss.exe command (run tss.exe -h to see list of commands)
     * @return          the output from tss.exe as a String
     * @since           1.0
     */
    private String executeCommand(String command) throws Exception {
        Runtime rt = Runtime.getRuntime();
        String response = "";
        BufferedReader bufferedReader = null;
        try {

            Process commandProcess = rt.exec(sdkFilePath + "tss.exe " + command, null);
            commandProcess.waitFor();
            bufferedReader = new BufferedReader(new InputStreamReader(commandProcess.getInputStream()));
            response = IOUtils.toString(bufferedReader);
        } catch (IOException | InterruptedException e) {
            System.err.println("Error running command " + command + " : " + e.getMessage());
        }
        finally {
            if(bufferedReader != null)
                bufferedReader.close();
        }
        return response;
    }

    /**
     * The Thycotic SDK requires an initial call to configure the connection to Secret Server. Calls the init command
     * which should only need to happen once per installation, which applies the settings in the qualifier.properties file.
     * After initialization is succesful the config file is deleted.
     *
     * @since   1.0
     */
    private void initializeSdk() throws Exception {
        String qualiferConfigFile = "qualifier.properties";
        try {
            Properties props = getConfigurationProperties("qualifier.properties");
            //Setting up the process for Authentication for the Secret Server (Required Once per Machine)
            String keyArgument = props.getProperty("datasource.secretServerKey");
            if ((keyArgument != null) && (!keyArgument.isEmpty())) {
                keyArgument = " -k " + keyArgument;
            }
            System.out.println("SDK not configured, initializing.");
            executeCommand("init --url " + props.getProperty("datasource.secretServerUrl") + " -r " + props.getProperty("datasource.secretServerRule") + keyArgument);
            //Setting up the process for the Cache Strategy after Authenticating with Secret Server (Required Once per Machine)
            executeCommand("cache --strategy " + props.getProperty("datasource.secretServerCacheStrat") + " --age " + props.getProperty("datasource.secretServerCacheAge"));
            System.out.println("Finished initializing SDK");
        }
        catch (Exception e) {
            System.err.println("Error initializing SDK.");
            throw e;
        }
        File configFile = new File(sdkFilePath + qualiferConfigFile);
        String absolutePath = configFile.getAbsolutePath();
        if (configFile.isFile()) {
            if (!configFile.delete()) {
                System.err.println("Error deleting file at: " + absolutePath);
            }
        }
    }

    /**
     * Normalizes a field name to match conventions used by the Thycotic REST API and SDK. Field names are lowercased
     * and spaces are replaced with underscores
     *
     * @param fieldName the secret field name as specified in the mappings in qualifer.config
     * @return          a cleaned field name
     * @since           1.0
     */
    private String getFieldSlug(String fieldName) {
        return fieldName.toLowerCase().replace(" ", "-");
    }

    /**
     * Retrieves a Secret from Secret Server based on id passed from ServiceNow and maps the Secret field values
     * to a properties object.
     *
     * @param id    the Secret id passed in from ServiceNow
     * @param type  the ServiceNow credential type which corresponds to the mappings in qualifer.config
     * @since       1.0
     */
    private void loadProps(String id, String type) throws Exception {
        if (fProps == null) {
            fProps = new Properties();
        }

        //Initialization of Base Variables from ServiceNow
        int secretID = Integer.parseInt(id);
        String typeLookup = type + ".";

        Properties templateMappings = getConfigurationProperties("secretmap.properties");

        //Setup the Thycotic SDK authentication if the config file doesn't exist
        if (!new File("credentials.config").isFile()) {
            initializeSdk();
        }

        //Execution of the secret command to pull a secret from TSS CLI Client
        String fullRec = executeCommand("secret -s " + secretID + " -ad");

        if (fullRec.startsWith("Secret Server credentials not present.")) {
            System.err.println("Discovery Error: " + fullRec);
        } else if (fullRec.startsWith("{")) {
        //Parsing the SNOW fields from the JSON Object and add the properties to the fProps key pair.
        try {
            System.out.println("Loading properties from Secret: " + secretID);
            JSONObject json = (JSONObject) new JSONParser().parse(fullRec);
            String key;
            for (Enumeration property = templateMappings.propertyNames(); property.hasMoreElements(); ) {
                key = (String) property.nextElement();
                if (key.startsWith(typeLookup)) {
                    String secretFieldName = getFieldSlug(templateMappings.getProperty(key));
                    if (json.containsKey(secretFieldName)) {
                        String secretFieldValue = "";
                        if (key.contains(VAL_USER) && json.containsKey(templateMappings.getProperty(typeLookup + "domain"))) {
                            secretFieldValue = json.get(templateMappings.getProperty(typeLookup + "domain")) + "\\" + json.get(secretFieldName);
                        } else {
                            secretFieldValue = (String) json.get(secretFieldName);
                            if (secretFieldValue.toLowerCase().contains("not valid for display")) {
                                // File fields need to be retrieved directly
                                secretFieldValue = executeCommand("secret -s " + secretID + " -f " + secretFieldName);
                            }
                        }
                        fProps.setProperty(key, secretFieldValue);
                    }
                }
            }
        } catch (ParseException e) {
            e.printStackTrace();
            }
        } else {
            System.err.println("Discovery Error: " + fullRec);
        }
    }

    /**
     * Retrieves a Secret from Secret Server based on id passed from ServiceNow and maps the Secret field values
     * to a properties object. Must be public because the ServiceNow mid server loads the jar and calls this method.
     *
     * @param args  arguments for secret id and credential type passed in from ServiceNow
     * @return      a key-value map with keys defined by ServiceNow and values from the retreived Secret
     * @since       1.0
     */
    public Map resolve(Map args) {
        Map result = new HashMap();
        try {
            String id = (String) args.get(ARG_ID);
            String type = (String) args.get(ARG_TYPE);
            String keyPrefix = type + ".";

            sdkFilePath = getSdkFilePath();

            loadProps(id, type);

            result.put(VAL_USER, fProps.get(keyPrefix + VAL_USER));
            result.put(VAL_PSWD, fProps.get(keyPrefix + VAL_PSWD));
            result.put(VAL_PKEY, fProps.get(keyPrefix + VAL_PKEY));
            result.put(VAL_PASSPHRASE, fProps.get(keyPrefix + VAL_PASSPHRASE));
            result.put(VAL_AUTHPROTO, fProps.get(keyPrefix + VAL_AUTHPROTO));
            result.put(VAL_AUTHKEY, fProps.get(keyPrefix + VAL_AUTHKEY));
            result.put(VAL_PRIVPROTO, fProps.get(keyPrefix + VAL_PRIVPROTO));
            result.put(VAL_PRIVKEY, fProps.get(keyPrefix + VAL_PRIVKEY));
        }
        catch (Exception e) {
            e.printStackTrace();
        }
        return result;
    }

    /**
     * Version information used by ServiceNow
     *
     * @return  hardcoded version number
     */
    public String getVersion() {
        return "1.0";
    }

    /**
     * Main method for manual testing and diagnostics via the command line.
     */
    public static void main(String[] args) throws Exception {
        CredentialResolver obj = new CredentialResolver();
        Map arguments = new HashMap();
        arguments.put("id", args[0]);
        arguments.put("type", args[1]);
        obj.resolve(arguments);
        System.out.println("The following keys were returned: ");
        for (Object key : obj.fProps.keySet()) {
            System.out.println(key);
        }
    }
}