package com.devicehive.websockets.handlers;

import com.devicehive.auth.HivePrincipal;
import com.devicehive.configuration.Constants;
import com.devicehive.configuration.Messages;
import com.devicehive.exceptions.HiveException;
import com.devicehive.model.DeviceNotification;
import com.devicehive.model.eventbus.events.NotificationEvent;
import com.devicehive.model.rpc.Action;
import com.devicehive.model.rpc.NotificationSubscribeResponse;
import com.devicehive.model.websockets.InsertNotification;
import com.devicehive.model.wrappers.DeviceNotificationWrapper;
import com.devicehive.service.DeviceNotificationService;
import com.devicehive.service.DeviceService;
import com.devicehive.shim.api.Response;
import com.devicehive.util.ServerResponsesFactory;
import com.devicehive.vo.DeviceVO;
import com.devicehive.websockets.converters.WebSocketResponse;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.web.socket.WebSocketSession;

import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

import static com.devicehive.configuration.Constants.NOTIFICATION;
import static com.devicehive.configuration.Constants.SUBSCRIPTION_ID;
import static com.devicehive.json.strategies.JsonPolicyDef.Policy.NOTIFICATION_TO_DEVICE;
import static com.devicehive.messages.handler.WebSocketClientHandler.sendMessage;
import static javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static javax.servlet.http.HttpServletResponse.SC_FORBIDDEN;

@Component
public class NotificationHandlers {
    private static final Logger logger = LoggerFactory.getLogger(NotificationHandlers.class);

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private DeviceNotificationService notificationService;

    @Autowired
    private Gson gson;


    @PreAuthorize("hasAnyRole('ADMIN', 'CLIENT', 'KEY') and hasPermission(null, 'GET_DEVICE_NOTIFICATION')")
    public WebSocketResponse processNotificationSubscribe(JsonObject request,
                                                          WebSocketSession session) throws InterruptedException {
        Date timestamp = gson.fromJson(request.get(Constants.TIMESTAMP), Date.class);
        Set<String> devices = gson.fromJson(request.get(Constants.DEVICE_GUIDS), new TypeToken<Set<String>>() {}.getType());
        Set<String> names = gson.fromJson(request.get(Constants.NAMES), new TypeToken<Set<String>>() {}.getType());
        String deviceId = request.get(Constants.DEVICE_GUID).getAsString();

        Assert.notEmpty(devices);
        logger.debug("notification/subscribe requested for devices: {}, {}. Timestamp: {}. Names {} Session: {}",
                devices, deviceId, timestamp, names, session.getId());

        devices = prepareActualList(devices, deviceId);

        String subscriptionId = UUID.randomUUID().toString();

        CountDownLatch subscriptionLatch = new CountDownLatch(devices.size());
        Set<DeviceNotification> notifications = new HashSet<>();
        Consumer<Response> callback = response -> {
            String resAction = response.getBody().getAction();
            if (resAction.equals(Action.NOTIFICATION_SUBSCRIBE_RESPONSE.name())) {
                NotificationSubscribeResponse subscribeResponse = response.getBody().cast(NotificationSubscribeResponse.class);
                notifications.addAll(subscribeResponse.getNotifications());
                subscriptionLatch.countDown();
            } else if (resAction.equals(Action.NOTIFICATION_EVENT.name())) {
                NotificationEvent event = response.getBody().cast(NotificationEvent.class);
                JsonObject json = ServerResponsesFactory.createNotificationInsertMessage(event.getNotification(), subscriptionId);
                sendMessage(json, session);
            } else {
                logger.warn("Unknown action received from backend {}", resAction);
            }
        };

        notificationService.submitDeviceSubscribeNotification(subscriptionId, devices, names, timestamp, callback);

        subscriptionLatch.await();

        logger.debug("notification/subscribe done for devices: {}, {}. Timestamp: {}. Names {} Session: {}",
                devices, deviceId, timestamp, names, session.getId());

        //TODO send notifications

        WebSocketResponse response = new WebSocketResponse();
        response.addValue(SUBSCRIPTION_ID, subscriptionId, null);
        return response;
    }

    private Set<String> prepareActualList(Set<String> deviceIdSet, final String deviceId) {
        if (deviceId == null && deviceIdSet == null) {
            return null;
        }
        if (deviceIdSet != null && deviceId == null) {
            deviceIdSet.remove(null);
            return deviceIdSet;
        }

        if (deviceIdSet == null) {
            return new HashSet<String>() {
                {
                    add(deviceId);
                }

                private static final long serialVersionUID = 955343867580964077L;
            };

        }
        throw new HiveException(Messages.INVALID_REQUEST_PARAMETERS, SC_BAD_REQUEST);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'CLIENT', 'KEY') and hasPermission(null, 'GET_DEVICE_NOTIFICATION')")
    public WebSocketResponse processNotificationUnsubscribe(JsonObject request,
                                                            WebSocketSession session) {
        return new WebSocketResponse();
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'CLIENT', 'KEY') and hasPermission(null, 'CREATE_DEVICE_NOTIFICATION')")
    public WebSocketResponse processNotificationInsert(JsonObject request,
                                                       WebSocketSession session) {
        String deviceGuid = request.get(Constants.DEVICE_GUID).getAsString();
        DeviceNotificationWrapper notificationSubmit = gson.fromJson(request.get(Constants.NOTIFICATION), DeviceNotificationWrapper.class);

        logger.debug("notification/insert requested. Session {}. Guid {}", session, deviceGuid);
        HivePrincipal principal = (HivePrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (notificationSubmit == null || notificationSubmit.getNotification() == null) {
            logger.debug(
                    "notification/insert proceed with error. Bad notification: notification is required.");
            throw new HiveException(Messages.NOTIFICATION_REQUIRED, SC_BAD_REQUEST);
        }

        DeviceVO device;
        if (deviceGuid == null) {
            device = principal.getDevice();
        } else {
            device = deviceService.findByGuidWithPermissionsCheck(deviceGuid, principal);
        }
        if (device == null) {
            logger.debug("notification/insert canceled for session: {}. Guid is not provided", session);
            throw new HiveException(Messages.DEVICE_GUID_REQUIRED, SC_FORBIDDEN);
        }
        if (device.getNetwork() == null) {
            logger.debug("notification/insert. No network specified for device with guid = {}", deviceGuid);
            throw new HiveException(String.format(Messages.DEVICE_IS_NOT_CONNECTED_TO_NETWORK, deviceGuid), SC_FORBIDDEN);
        }
        DeviceNotification message = notificationService.convertToMessage(notificationSubmit, device);
        notificationService.submitDeviceNotification(message, device);
        logger.debug("notification/insert proceed successfully. Session {}. Guid {}", session, deviceGuid);

        WebSocketResponse response = new WebSocketResponse();
        response.addValue(NOTIFICATION, new InsertNotification(message.getId(), message.getTimestamp()), NOTIFICATION_TO_DEVICE);
        return response;
    }
}
