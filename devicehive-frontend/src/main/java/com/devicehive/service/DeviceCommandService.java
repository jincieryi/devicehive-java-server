package com.devicehive.service;

import com.devicehive.messages.handler.ClientHandler;
import com.devicehive.model.DeviceCommand;
import com.devicehive.model.eventbus.events.CommandEvent;
import com.devicehive.model.rpc.*;
import com.devicehive.model.wrappers.DeviceCommandWrapper;
import com.devicehive.service.helpers.ResponseConsumer;
import com.devicehive.service.time.TimestampService;
import com.devicehive.shim.api.Request;
import com.devicehive.shim.api.Response;
import com.devicehive.shim.api.client.RpcClient;
import com.devicehive.util.HiveValidator;
import com.devicehive.util.ServerResponsesFactory;
import com.devicehive.vo.DeviceVO;
import com.devicehive.vo.UserVO;
import com.google.gson.JsonObject;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
public class DeviceCommandService {

    private static final Logger logger = LoggerFactory.getLogger(DeviceCommandService.class);

    @Autowired
    private TimestampService timestampService;

    @Autowired
    private HiveValidator hiveValidator;

    @Autowired
    private RpcClient rpcClient;

    @Autowired
    private DeviceService deviceService;

    @SuppressWarnings("unchecked")
    public CompletableFuture<Optional<DeviceCommand>> find(Long id, String guid) {
        CommandSearchRequest searchRequest = new CommandSearchRequest();
        searchRequest.setId(id);
        searchRequest.setGuid(guid);

        CompletableFuture<Response> future = new CompletableFuture<>();
        rpcClient.call(Request.newBuilder()
                .withBody(searchRequest)
                .build(), new ResponseConsumer(future));
        return future.thenApply(r -> r.getBody().cast(CommandSearchResponse.class).getCommands().stream().findFirst());
    }

    public CompletableFuture<List<DeviceCommand>> find(Collection<String> guids, Collection<String> names,
                                                       Date timestamp, String status, Integer take, Boolean hasResponse) {
        List<CompletableFuture<Response>> futures = guids.stream()
                .map(guid -> {
                    CommandSearchRequest searchRequest = new CommandSearchRequest();
                    searchRequest.setGuid(guid);
                    searchRequest.setNames(new HashSet<>(names));
                    searchRequest.setTimestamp(timestamp);
                    searchRequest.setStatus(status);
                    searchRequest.setTake(take);
                    searchRequest.setHasResponse(hasResponse);
                    return searchRequest;
                })
                .map(searchRequest -> {
                    CompletableFuture<Response> future = new CompletableFuture<>();
                    rpcClient.call(Request.newBuilder()
                            .withBody(searchRequest)
                            .withPartitionKey(searchRequest.getGuid())
                            .build(), new ResponseConsumer(future));
                    return future;
                })
                .collect(Collectors.toList());

        // List<CompletableFuture<Response>> => CompletableFuture<List<DeviceCommand>>
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[futures.size()]))
                .thenApply(v -> futures.stream()
                        .map(CompletableFuture::join)                                  // List<CompletableFuture<Response>> => CompletableFuture<List<Response>>
                        .map(r -> ((CommandSearchResponse) r.getBody()).getCommands()) // CompletableFuture<List<Response>> => CompletableFuture<List<List<DeviceCommand>>>
                        .flatMap(Collection::stream)                                   // CompletableFuture<List<List<DeviceCommand>>> => CompletableFuture<List<DeviceCommand>>
                        .collect(Collectors.toList()));
    }

    public CompletableFuture<DeviceCommand> insert(DeviceCommandWrapper commandWrapper, DeviceVO device, UserVO user) {
        DeviceCommand command = convertWrapperToCommand(commandWrapper, device, user);

        CompletableFuture<Response> future = new CompletableFuture<>();
        rpcClient.call(Request.newBuilder()
                .withBody(new CommandInsertRequest(command))
                .build(), new ResponseConsumer(future));
        return future.thenApply(r -> ((CommandInsertResponse) r.getBody()).getDeviceCommand());
    }

    public CompletableFuture<Void> update(Long commandId, String deviceGuid, DeviceCommandWrapper commandWrapper) {
        return find(commandId, deviceGuid)
                .thenApply(opt -> opt.orElse(null)) //todo would be preferable to use .thenApply(opt -> opt.orElseThrow(() -> new NoSuchElementException("Command not found"))), but does not build on some machines
                .thenAccept(cmd -> doUpdate(cmd, commandWrapper));
    }

    public String submitCommandSubscribe(final Set<String> devices,
                                         final Set<String> names,
                                         final Date timestamp,
                                         final ClientHandler clientHandler) throws InterruptedException {
        final String subscriptionId = UUID.randomUUID().toString();
        Set<CommandSubscribeRequest> subscribeRequests = devices.stream()
                .map(device -> new CommandSubscribeRequest(subscriptionId, device, names, timestamp))
                .collect(Collectors.toSet());

        CountDownLatch responseLatch = new CountDownLatch(subscribeRequests.size());
        Set<DeviceCommand> commands = new HashSet<>();
        for (CommandSubscribeRequest subscribeRequest : subscribeRequests) {
            Consumer<Response> callback = response -> {
                String resAction = response.getBody().getAction();
                if (resAction.equals(Action.COMMAND_SUBSCRIBE_RESPONSE.name())) {
                    CommandSubscribeResponse subscribeResponse = (CommandSubscribeResponse) response.getBody();
                    commands.addAll(subscribeResponse.getCommands());
                    responseLatch.countDown();
                } else if (resAction.equals(Action.COMMAND_EVENT.name())) {
                    CommandEvent event = (CommandEvent) response.getBody();
                    JsonObject json = ServerResponsesFactory.createCommandInsertMessage(event.getCommand(), subscriptionId);
                    clientHandler.sendMessage(json);
                } else {
                    logger.warn("Unknown action received from backend {}", resAction);
                }
            };

            Request request = Request.newBuilder()
                    .withBody(subscribeRequest)
                    .withPartitionKey(subscribeRequest.getDevice())
                    .withSingleReply(false)
                    .build();
            rpcClient.call(request, callback);
        }
        responseLatch.await();
        if (!commands.isEmpty()) {
            //todo send existing commands to device after subscription response
        }
        return subscriptionId;
    }

    public void submitCommandUnsubscribe(String subId, Set<String> deviceGuids) {
        CommandUnsubscribeRequest unsubscribeRequest = new CommandUnsubscribeRequest(subId, deviceGuids);
        Request request = Request.newBuilder()
                .withBody(unsubscribeRequest)
                .build();
        rpcClient.push(request);
    }

    public CompletableFuture<Pair<String, DeviceCommand>> submitSubscribeOnUpdate(long commandId, String guid) {
        CompletableFuture<Response> future = new CompletableFuture<>();
        rpcClient.call(Request.newBuilder()
                .withBody(new CommandUpdateSubscribeRequest(commandId, guid))
                .build(), new ResponseConsumer(future));
        return future.thenApply(r -> {
            CommandUpdateSubscribeResponse response = r.getBody().cast(CommandUpdateSubscribeResponse.class);
            return Pair.of(response.getSubscriptionId(), response.getDeviceCommand());
        });
    }

    private CompletableFuture<Void> doUpdate(DeviceCommand cmd, DeviceCommandWrapper commandWrapper) {
        if (cmd == null) {
            throw new NoSuchElementException("Command not found");
        }
        cmd.setIsUpdated(true);

        if (commandWrapper.getCommand() != null) {
            cmd.setCommand(commandWrapper.getCommand().orElse(null));
        }
        if (commandWrapper.getParameters() != null) {
            cmd.setParameters(commandWrapper.getParameters().orElse(null));
        }
        if (commandWrapper.getLifetime() != null) {
            cmd.setLifetime(commandWrapper.getLifetime().orElse(null));
        }
        if (commandWrapper.getStatus() != null) {
            cmd.setStatus(commandWrapper.getStatus().orElse(null));
        }
        if (commandWrapper.getResult() != null) {
            cmd.setResult(commandWrapper.getResult().orElse(null));
        }

        hiveValidator.validate(cmd);

        CompletableFuture<Response> future = new CompletableFuture<>();
        rpcClient.call(Request.newBuilder()
                .withBody(new CommandUpdateRequest(cmd))
                .build(), new ResponseConsumer(future));
        return future.thenApply(response -> null);
    }

    private DeviceCommand convertWrapperToCommand(DeviceCommandWrapper commandWrapper, DeviceVO device, UserVO user) {
        DeviceCommand command = new DeviceCommand();
        command.setId(Math.abs(new Random().nextInt()));
        command.setDeviceGuid(device.getGuid());
        command.setIsUpdated(false);
        command.setTimestamp(timestampService.getTimestamp());

        if (user != null) {
            command.setUserId(user.getId());
        }
        if (commandWrapper.getCommand() != null) {
            command.setCommand(commandWrapper.getCommand().orElseGet(null));
        }
        if (commandWrapper.getParameters() != null) {
            command.setParameters(commandWrapper.getParameters().orElse(null));
        }
        if (commandWrapper.getLifetime() != null) {
            command.setLifetime(commandWrapper.getLifetime().orElse(null));
        }
        if (commandWrapper.getStatus() != null) {
            command.setStatus(commandWrapper.getStatus().orElse(null));
        }
        if (commandWrapper.getResult() != null) {
            command.setResult(commandWrapper.getResult().orElse(null));
        }

        hiveValidator.validate(command);
        return command;
    }
}
