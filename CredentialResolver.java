/**************************************************************
* Application: 
* Thycotic SecretServer Integration - ServiceNow
* Author: 
* Justin Roberts
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
* Qualifier & TSS hard coded to D:\CLI, can be modified below
* qualifier.config, stored with the CLI tool, options include:
* SecretServer URL
* SecretServer Rule Name
* SecretServer Onboarding Key
* SecretServer Cache Strategy
* SecretServer Cache Dump (Minutes)
**************************************************************/

package com.snc.discovery;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.apache.commons.io.IOUtils;
import org.json.JSONObject;

public class CredentialResolver {

  //String for location of CLI & qualifier.config file
  public static String LocationCLI = "D:\\CLI\\";
  //Required for mapping to ServiceNow
  public static final String ARG_ID = "id";
  public static final String ARG_IP = "ip";
  public static final String ARG_TYPE = "type";
  public static final String ARG_MID = "mid";
  public static String VAL_USER = "user";
  public static String VAL_PSWD = "pswd";
  public static String VAL_PASSPHRASE = "passphrase";
  public static String VAL_PKEY = "pkey";
  public static String VAL_AUTHPROTO = "authprotocol";
  public static String VAL_AUTHKEY = "authkey";
  public static String VAL_PRIVPROTO = "privprotocol";
  public static String VAL_PRIVKEY = "privkey";
  public Properties fProps;

  //Required Constructor
  public CredentialResolver() {
  }

  //Auth Method for the Authenticating to Thycotic Secret Server, Depending on Properties loaded in Qualifier.config.
  private void Authenticate() {
    //Setting up the runtime environment for the CLI Execution plans.
    Runtime rt = Runtime.getRuntime();
  
    //Loading all properties from the Properties file: Qualifier.config
    Properties props = new Properties();
    try {
      props.load(new FileInputStream(LocationCLI + "qualifier.config"));
    } catch (IOException e) {
      System.err.println("Cannot find qualifier.config, execption: " + e.getMessage());
    }

    //Setting up the process for Authentication for the Secret Server (Required Once per Machine)
    Process initCLI;
    try {
      //Executing the init command to the TSS CLI Client
      initCLI = rt.exec(LocationCLI + "tss.exe init --url " + props.getProperty("datasource.secretServerUrl") + " -r " + props.getProperty("datasource.secretServerRule") + " -k " + props.getProperty("datasource.secretServerKey"));
      //Reading the output to be placed in the wrapper.log file
      BufferedReader brInitCLI = new BufferedReader(new InputStreamReader(initCLI.getInputStream()));
      System.out.println(IOUtils.toString(brInitCLI));
      brInitCLI.close();
     //Clean up of the objects, disposal, and error handling
    } catch (IOException e) {
      System.out.println("Discovery Error: TSS CLI Initalization failure.");
      System.out.println("Discovery Error: " + e.getMessage());
    }

    //Setting up the process for the Cache Strategy after Authenticating with Secret Server (Required Once per Machine)
    Process initCLIStrat;
    try {
      //Executing the strategy command to the TSS CLI Client
      initCLIStrat = rt.exec(LocationCLI + "tss.exe cache --strategy " + props.getProperty("datasource.secretServerCacheStrat") + " --age " + props.getProperty("datasource.secretServerCacheAge"));
      //Reading the output to be placed in the wrapper.log file
      BufferedReader brInitCLIStrat = new BufferedReader(new InputStreamReader(initCLIStrat.getInputStream()));
      System.out.println(IOUtils.toString(brInitCLIStrat));
      brInitCLIStrat.close();
      //Clean up of the objects, disposal, and error handling
    } catch (IOException e) {
      System.out.println("Discovery Error: TSS CLI Strategy failure");
      System.out.println("Discovery Error: " + e.getMessage());
    }
  }

  //Call to Thycotic CLI with the ServiceNow ID and Type from Credentials Table.
  private void loadProps(String id, String type) {
    if(fProps == null) {
      fProps = new Properties();
    }

    //Initialization of Base Variables from ServiceNow
    int secretID = Integer.parseInt(id);
    String kPre = "" + secretID + "." + type + ".";

    //Setting up the runtime environment for the CLI Execution plans.
    Runtime rt = Runtime.getRuntime();

    //Setting up the process for the execution of pulling a secret from the TSS CLI Client.
    Process pullAllFields;
    try {
      //Execution of the secret command to pull a secret from TSS CLI Client
      pullAllFields = rt.exec(LocationCLI + "tss.exe secret -s " + secretID + " -ad");
      //Reading the output of the TSS CLI Client to Authenticate or Pull a secret
      BufferedReader inputAllFields = new BufferedReader(new InputStreamReader(pullAllFields.getInputStream()));
      String fullRec = IOUtils.toString(inputAllFields);
      //Reading the output, if the server is not Authenticated, then call the Authenticate() Method.
      if (fullRec.startsWith("Secret Server credentials not present.")){
        System.out.println("Discovery Err: " + fullRec);
        //Calling Authenticate Method
        Authenticate();
        inputAllFields.close();
        //Reading the output, if the String starts with a {, verification for a JSON String
      } else if (fullRec.startsWith("{") ) {
        //Creation of a JSON Object with the output from the TSS CLI Client
        JSONObject json = new JSONObject(fullRec.toString());
        //Parsing the username and password from the JSON Object, and adding the properties to the fProps key pair.
        if (json.has("domain")) {
          String domainUsername = json.getString("domain") + "\\" + json.getString("username");
          fProps.setProperty(kPre + VAL_USER,domainUsername);
          fProps.setProperty(kPre + VAL_PSWD,json.getString("password"));
        } else {
          fProps.setProperty(kPre + VAL_USER,json.getString("username"));
          fProps.setProperty(kPre + VAL_PSWD,json.getString("password"));
        }
        inputAllFields.close();
      //Anything besides the two strings checked for above, print out response in the wrapper.log file
      } else {
        System.out.println("Discovery Err: " + fullRec);
        inputAllFields.close();
      }
      //Clean up of the objects, disposal, and error handling
    } catch (IOException e) {
      System.out.println("Discovery Error: " + e.getMessage());
    }
    //Uncomment for testing
    //System.out.println("CredentialResolver, loadProps, Thycotic Key: " + fProps);
  }

  //Configures Key / Username / Password / Etc into consumable format for ServiceNow.
  @SuppressWarnings({ "unchecked", "rawtypes" })
  public Map resolve(Map args) throws Exception {
    //Initialize variables for ServiceNow Consumption.
    String id = (String) args.get(ARG_ID);
    String type = (String) args.get(ARG_TYPE);
    String keyPrefix = id + "." + type + ".";

    if(id.equalsIgnoreCase("misbehave"))
      throw new RuntimeException("CredentialResolver, I've been a baaaaaaaaad CredentialResolver!");

    //Call loadProps to fill map per tagged credential.
    loadProps(id,type);

    Map result = new HashMap();
      result.put(VAL_USER, fProps.get(keyPrefix + VAL_USER));
      result.put(VAL_PSWD, fProps.get(keyPrefix + VAL_PSWD));
      result.put(VAL_PKEY, fProps.get(keyPrefix + VAL_PKEY));
      result.put(VAL_PASSPHRASE, fProps.get(keyPrefix + VAL_PASSPHRASE));
      result.put(VAL_AUTHPROTO, fProps.get(keyPrefix + VAL_AUTHPROTO));
      result.put(VAL_AUTHKEY, fProps.get(keyPrefix + VAL_AUTHKEY));
      result.put(VAL_PRIVPROTO, fProps.get(keyPrefix + VAL_PRIVPROTO));
      result.put(VAL_PRIVKEY, fProps.get(keyPrefix + VAL_PRIVKEY));
      //Uncomment for testing
      //System.out.println("CredentialResolver, Resolving credential id/type["+id+"/"+type+"] -> "+result.get(VAL_USER)+"/"+result.get(VAL_PSWD)+"/"+result.get(VAL_PASSPHRASE)+"/"+result.get(VAL_PKEY)+"/"+result.get(VAL_AUTHPROTO)+"/"+result.get(VAL_AUTHKEY)+"/"+result.get(VAL_PRIVPROTO)+"/"+result.get(VAL_PRIVKEY));
      return result;
  }

  //Version information required for ServiceNow
  public String getVersion() {
    return "1.0";
  }

  //Build mapping object and output in consumable format for ServiceNow
  public static void main(String[] args) throws Exception {
    //Creation of new Constructor, outputs per key in the resolve MAP above.
    CredentialResolver obj = new CredentialResolver();
    System.err.println("I spy the following credentials: ");
    for(Object key: obj.fProps.keySet()) {
      System.out.println(key+": " + obj.fProps.get(key));
    }
  }
}