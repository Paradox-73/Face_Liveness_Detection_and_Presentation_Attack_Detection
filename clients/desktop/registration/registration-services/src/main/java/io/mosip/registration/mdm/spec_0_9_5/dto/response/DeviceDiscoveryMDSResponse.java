package io.mosip.registration.mdm.spec_0_9_5.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DeviceDiscoveryMDSResponse {

	private String deviceId;
	private String deviceStatus;
	private String certification;
	private String serviceVersion;
	private String[] deviceSubId;
	private String callbackId;
	private String digitalId;
	private String deviceCode;
	private String[] specVersion;
	private String purpose;
	private Error error;
}
