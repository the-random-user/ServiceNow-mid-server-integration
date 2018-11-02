# ServiceNow-mid-server-integration
Secret Server integration for ServiceNow mid server asset Discovery

# Install Instructions
* Upload the ThycoticServiceNowCredentialResolver.jar to ServiceNow. 
* Restart the MID Server
* Install the Thycotic SDK on the same server as the ServiceNow MID Server.
* Set the TSS_LOCATION environment variable to the SDK location. If not set, the default location is C:\TSS.
* Copy qualifer.properties and secretmap.properties to the same directory as the Thycotic SDK and modify the datasource settings with your Secret Server specific settings.
* When ServiceNow runs discovery for the first time it will initialize the SDK using the datasource settings
* If initialization is successful, the qualifer.properties will be deleted.
 
# Build Instructions
* Install apache ant: https://ant.apache.org/
* From the command line in the build folder run: ant compile jar 
* The jar file will be generated in out/artifacts 
   
