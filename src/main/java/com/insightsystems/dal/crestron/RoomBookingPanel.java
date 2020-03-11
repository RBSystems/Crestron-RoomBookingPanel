package com.insightsystems.dal.crestron;

import com.avispl.symphony.api.dal.control.Controller;
import com.avispl.symphony.api.dal.dto.control.ControllableProperty;
import com.avispl.symphony.api.dal.dto.monitor.*;
import com.avispl.symphony.api.dal.monitor.Monitorable;
import com.avispl.symphony.api.dal.ping.Pingable;
import com.avispl.symphony.dal.communicator.RestCommunicator;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

import static java.util.Collections.singletonList;

public class RoomBookingPanel extends RestCommunicator implements Monitorable, Pingable, Controller {

    @Override
    protected void authenticate() throws Exception {
        if (this.logger.isDebugEnabled())
            this.logger.debug("Attempting login with credentials [User: " + getLogin() + " Password: "+ getPassword() + "]");
        doPost("userlogin.html", "login=" + getLogin() + "&passwd=" + getPassword());
        //if password is correct, response with be status 200 and there will be set-cookie headers with session tokens etc.
    }

    @Override
    protected void internalInit() throws Exception {
        this.setAuthenticationScheme(AuthenticationScheme.None); //Stop sending default authentication for no reason
        this.setTrustAllCertificates(true); //Crestron ssl cert usually throws error- bypassing here
        this.setProtocol("https"); //Force https to avoid user configuration error (Devices always use https)
        super.internalInit();
    }

    @Override
    protected void internalDestroy() {
        super.internalDestroy();
        try {
            this.disconnect();
        } catch (Exception ignored){}
    }

    @Override
    public List<Statistics> getMultipleStatistics() throws Exception {
        Map<String,String> deviceStatistics = new HashMap<String, String>();
        Map<String,String> deviceControls = new HashMap<String,String>();
        ExtendedStatistics statistics = new ExtendedStatistics();
        String devResponse; //String to store response from device

        JsonFactory jsonFactory = new JsonFactory();
        JsonParser jsonParser;
        try {
            devResponse = doGet("Device/SchedulingPanel/Monitoring");
        } catch (Exception e) {
            //If error is code 403 session token is expired or non existent
            if (e.getCause().toString().contains("403 Forbidden")) {
                if (this.logger.isDebugEnabled())
                    this.logger.debug("403 Error Response received from device. Session token expired or credentials incorrect.");

                this.authenticate();
                //Attempt to get data again, this time not catching exceptions as device should be authenticated.
                devResponse = doGet("Device/SchedulingPanel/Monitoring");
            } else //If exception is not expired session throw the error.
                throw e;
        }
        jsonParser = jsonFactory.createParser(devResponse);
        if (jsonParser.nextToken() != JsonToken.START_OBJECT)
            throw new IOException("REST API response formatted incorrectly. Unable to parse.");

        while (jsonParser.nextToken() != JsonToken.END_OBJECT) {
            final String key = jsonParser.getCurrentName() ;
            jsonParser.nextToken();
            switch (key){ //If key is a value we want to monitor add it
                case "ConnectionStatus":
                case "CalendarSyncStatus":
                case "ConnectionStatusMessage":
                case "State":
                    deviceStatistics.put(key,jsonParser.getText());
            }
        }
        deviceStatistics.put("PanelSyncing",timeInThreshold(deviceStatistics.get("CalendarSyncStatus").substring(20)));
        devResponse = doGet("Device/SchedulingPanel/Config/Scheduling/Exchange");
        jsonParser = jsonFactory.createParser(devResponse);
        if (jsonParser.nextToken() != JsonToken.START_OBJECT)
            throw new IOException("REST API response formatted incorrectly. Unable to parse.");

        while (jsonParser.nextToken() != JsonToken.END_OBJECT) {
            final String key = jsonParser.getCurrentName();
            jsonParser.nextToken();
            if (key.equals("Username")) {
                deviceStatistics.put("ExchangeUsername", jsonParser.getText());
                break;
            }
        }

        devResponse = doGet("Device/DeviceInfo/");
        jsonParser = jsonFactory.createParser(devResponse);
        if (jsonParser.nextToken() != JsonToken.START_OBJECT)
            throw new IOException("REST API response formatted incorrectly. Unable to parse.");

        while (jsonParser.nextToken() != JsonToken.END_OBJECT) {
            final String key = jsonParser.getCurrentName();
            jsonParser.nextToken();
            switch (key) {
                case "PufVersion":
                case "MacAddress":
                case "SerialNumber":
                case "DeviceName":
                case "BuildDate":
                    deviceStatistics.put(key,jsonParser.getText());
            }
        }
        deviceStatistics.put("reboot","0");
        deviceControls.put("reboot", "push");

        statistics.setStatistics(deviceStatistics);
        statistics.setControl(deviceControls);
        return singletonList(statistics);
    }

    private String timeInThreshold(String dateString) throws ParseException {
        Date lastSync = new Date();
        SimpleDateFormat sdf = new SimpleDateFormat("hh:mm aa - MMMMM dd, yyyy", Locale.ENGLISH);
        sdf.setTimeZone(TimeZone.getTimeZone("Australia/Melbourne"));
        lastSync.setTime(sdf.parse(dateString).getTime());
        lastSync.setTime(lastSync.getTime() + 720000); // Add 12 mins to lastSync time
        return lastSync.getTime() <= new Date().getTime() ? "false":"true";
    }

    @Override
    public void controlProperty(ControllableProperty controllableProperty) throws Exception {
        if (controllableProperty == null)
            return;

        if (controllableProperty.getProperty().equalsIgnoreCase("reboot")) {
            String response = doPost("Device/DeviceOperations", "{\"Device\":{\"DeviceOperations\":{\"Reboot\":true}}}"); //send reboot command to the devices
            if (!response.contains("OK") && this.logger.isErrorEnabled()){
                this.logger.error("Device Reboot failed with response from device: " + response);
            }
        }
    }

    @Override
    public void controlProperties(List<ControllableProperty> list) throws Exception {
        list.forEach(p -> {
            try {
                controlProperty(p);
            } catch (Exception ignored) {}
        });
    }

    public static void main(String[] args) throws Exception {
        RoomBookingPanel device = new RoomBookingPanel();
        device.setLogin("admin");
        device.setPassword("19881988");
        device.init();

        device.getMultipleStatistics();
    }
}
