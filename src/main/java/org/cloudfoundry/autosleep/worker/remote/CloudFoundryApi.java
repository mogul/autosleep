/*
 * Autosleep
 * Copyright (C) 2016 Orange
 * Authors: Benjamin Einaudi   benjamin.einaudi@orange.com
 *          Arnaud Ruffin      arnaud.ruffin@orange.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.cloudfoundry.autosleep.worker.remote;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.cloudfoundry.autosleep.config.Config;
import org.cloudfoundry.autosleep.config.Config.CloudFoundryAppState;
import org.cloudfoundry.autosleep.dao.model.ApplicationInfo;
import org.cloudfoundry.autosleep.worker.remote.model.ApplicationActivity;
import org.cloudfoundry.autosleep.worker.remote.model.ApplicationIdentity;
import org.cloudfoundry.client.v2.applications.ApplicationEntity;
import org.cloudfoundry.client.v2.applications.GetApplicationRequest;
import org.cloudfoundry.client.v2.applications.GetApplicationResponse;
import org.cloudfoundry.client.v2.applications.ListApplicationRoutesRequest;
import org.cloudfoundry.client.v2.applications.ListApplicationRoutesResponse;
import org.cloudfoundry.client.v2.applications.ListApplicationsRequest;
import org.cloudfoundry.client.v2.applications.ListApplicationsResponse;
import org.cloudfoundry.client.v2.applications.UpdateApplicationRequest;
import org.cloudfoundry.client.v2.events.EventEntity;
import org.cloudfoundry.client.v2.events.EventResource;
import org.cloudfoundry.client.v2.events.ListEventsRequest;
import org.cloudfoundry.client.v2.events.ListEventsResponse;
import org.cloudfoundry.client.v2.routes.ListRouteApplicationsRequest;
import org.cloudfoundry.client.v2.routes.ListRouteApplicationsResponse;
import org.cloudfoundry.client.v2.servicebindings.CreateServiceBindingRequest;
import org.cloudfoundry.client.v2.servicebindings.DeleteServiceBindingRequest;
import org.cloudfoundry.client.v2.serviceinstances.BindServiceInstanceToRouteRequest;
import org.cloudfoundry.logging.LogMessage;
import org.cloudfoundry.logging.RecentLogsRequest;
import org.cloudfoundry.spring.client.SpringCloudFoundryClient;
import org.cloudfoundry.spring.logging.SpringLoggingClient;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class CloudFoundryApi implements CloudFoundryApiService {

    @Getter
    public abstract class BaseSubscriber<T> implements Subscriber<T> {

        final AtomicReference<Throwable> error;

        final CountDownLatch latch;

        public BaseSubscriber(CountDownLatch latch, AtomicReference<Throwable> error) {
            this.latch = latch;
            this.error = error;
        }

        @Override
        public void onComplete() {
            latch.countDown();
        }

        @Override
        public void onError(Throwable throwable) {
            error.set(throwable);
            latch.countDown();
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }
    }

    @Autowired
    private SpringCloudFoundryClient cfClient;

    @Autowired
    private SpringLoggingClient logClient;

    @Override
    public void bindServiceInstance(ApplicationIdentity application, String serviceInstanceId) throws
            CloudFoundryException {
        log.debug("bindServiceInstance");
        try {
            cfClient.serviceBindings()
                    .create(
                            CreateServiceBindingRequest
                                    .builder()
                                    .applicationId(application.getGuid())
                                    .serviceInstanceId(serviceInstanceId)
                                    .build())
                    .get(Config.CF_API_TIMEOUT);
        } catch (RuntimeException r) {
            throw new CloudFoundryException(r);
        }
    }

    @Override
    public void bindServiceInstance(List<ApplicationIdentity> applications, String serviceInstanceId) throws
            CloudFoundryException {
        log.debug("bindServiceInstance list");
        for (ApplicationIdentity application : applications) {
            bindServiceInstance(application, serviceInstanceId);
        }
    }

    @Override
    public void bindServiceToRoute(String serviceInstanceId, String routeId) throws CloudFoundryException {
        log.debug("bindRouteToService");
        try {
            cfClient.serviceInstances()
                    .bindToRoute(BindServiceInstanceToRouteRequest.builder()
                            .serviceInstanceId(serviceInstanceId)
                            .routeId(routeId)
                            .build())
                    .get(Config.CF_API_TIMEOUT);
        } catch (RuntimeException r) {
            throw new CloudFoundryException(r);
        }
    }

    private ApplicationInfo.DiagnosticInfo.ApplicationEvent buildAppEvent(EventResource event) {
        if (event == null) {
            return null;
        } else {
            EventEntity cfEvent = event.getEntity();
            return ApplicationInfo.DiagnosticInfo.ApplicationEvent.builder()
                    .actee(cfEvent.getActee())
                    .actor(cfEvent.getActor())
                    .name(cfEvent.getType())
                    .timestamp(Instant.parse(cfEvent.getTimestamp()).toEpochMilli())
                    .type(cfEvent.getType())
                    .build();
        }
    }

    private ApplicationInfo.DiagnosticInfo.ApplicationLog buildAppLog(LogMessage cfLog) {
        return cfLog == null ? null : ApplicationInfo.DiagnosticInfo.ApplicationLog.builder()
                .message(cfLog.getMessage())
                .timestamp(cfLog.getTimestamp().getTime())
                .messageType(cfLog.getMessageType().toString())
                .sourceId(cfLog.getSourceId())
                .sourceName(cfLog.getSourceName())
                .build();
    }

    private boolean changeApplicationState(String applicationUuid, String targetState) throws CloudFoundryException {
        log.debug("changeApplicationState to {}", targetState);
        try {
            if (!targetState.equals(getApplicationState(applicationUuid))) {
                cfClient.applicationsV2()
                        .update(
                                UpdateApplicationRequest.builder()
                                        .applicationId(applicationUuid)
                                        .state(targetState)
                                        .build())
                        .get(Config.CF_API_TIMEOUT);
                return true;
            } else {
                log.warn("application {} already in state {}, nothing to do", applicationUuid, targetState);
                return false;
            }
        } catch (RuntimeException r) {
            throw new CloudFoundryException(r);
        }
    }

    @Override
    public ApplicationActivity getApplicationActivity(String appUid) throws CloudFoundryException {
        log.debug("Getting applicationActivity {}", appUid);

        //We need to call for appState, lastlogs and lastEvents
        final CountDownLatch latch = new CountDownLatch(3);
        final AtomicReference<Throwable> error = new AtomicReference<>(null);
        final AtomicReference<LogMessage> lastLogReference = new AtomicReference<>(null);
        final AtomicReference<EventResource> lastEventReference = new AtomicReference<>(null);
        final AtomicReference<GetApplicationResponse> appReference = new AtomicReference<>(null);

        Mono<GetApplicationResponse> getAppPublisher =
                cfClient.applicationsV2()
                        .get(GetApplicationRequest.builder()
                                .applicationId(appUid)
                                .build());

        Subscriber<GetApplicationResponse> getAppSubscriber = new BaseSubscriber<GetApplicationResponse>(latch, error) {

            @Override
            public void onNext(GetApplicationResponse getApplicationResponse) {
                appReference.set(getApplicationResponse);
            }

        };

        Mono<ListEventsResponse> listEventPublisher = this.cfClient.events()
                .list(ListEventsRequest.builder()
                        .actee(appUid)
                        .build());
        Subscriber<ListEventsResponse> listEventSubscriber = new BaseSubscriber<ListEventsResponse>(latch, error) {

            @Override
            public void onNext(ListEventsResponse listEventsResponse) {
                lastEventReference.set(listEventsResponse.getResources().get(0));
            }

        };

        Flux<LogMessage> lastLogPublisher = logClient.recent(RecentLogsRequest.builder()
                .applicationId(appUid)
                .build());

        Subscriber<LogMessage> lastLogSubscriber = new BaseSubscriber<LogMessage>(latch, error) {

            Instant mostRecentInstant;

            @Override
            public void onNext(LogMessage logMessage) {
                //logs are not ordered, must find the most recent
                Instant msgInstant = logMessage.getTimestamp().toInstant();
                if (mostRecentInstant == null || mostRecentInstant.isBefore(msgInstant)) {
                    mostRecentInstant = msgInstant;
                    lastLogReference.set(logMessage);
                }
            }
        };

        lastLogPublisher.subscribe(lastLogSubscriber);
        getAppPublisher.subscribe(getAppSubscriber);
        listEventPublisher.subscribe(listEventSubscriber);

        try {
            if (!latch.await(Config.CF_API_TIMEOUT.getSeconds(), TimeUnit.SECONDS)) {
                throw new IllegalStateException("Subscriber timed out");
            } else //noinspection ThrowableResultOfMethodCallIgnored
                if (error.get() != null) {
                    throw new CloudFoundryException(error.get());
                } else {
                    ApplicationEntity app = appReference.get().getEntity();
                    return ApplicationActivity.builder()
                            .application(ApplicationIdentity.builder()
                                    .guid(appUid)
                                    .name(app.getName())
                                    .build())
                            .lastEvent(buildAppEvent(lastEventReference.get()))
                            .lastLog(buildAppLog(lastLogReference.get()))
                            .state(app.getState())
                            .build();
                }
        } catch (InterruptedException e) {
            log.error(e.getMessage());
        }

        return null;
    }

    @Override
    public String getApplicationState(String applicationUuid) throws CloudFoundryException {
        log.debug("getApplicationState");
        try {
            return this.cfClient
                    .applicationsV2()
                    .get(GetApplicationRequest.builder()
                            .applicationId(applicationUuid)
                            .build())
                    .get(Config.CF_API_TIMEOUT)
                    .getEntity().getState();

        } catch (RuntimeException r) {
            throw new CloudFoundryException(r);
        }
    }

    @Override
    public List<String> listApplicationRoutes(String applicationUuid) throws CloudFoundryException {
        log.debug("listApplicationRoutes");
        try {
            ListApplicationRoutesResponse response = cfClient.applicationsV2()
                    .listRoutes(
                            ListApplicationRoutesRequest.builder()
                                    .applicationId(applicationUuid)
                                    .build())
                    .get(Config.CF_API_TIMEOUT);
            return response.getResources().stream().map(
                    routeResource -> routeResource.getMetadata().getId()
            ).collect(Collectors.toList());
        } catch (RuntimeException r) {
            throw new CloudFoundryException(r);
        }
    }

    @Override
    public List<ApplicationIdentity> listApplications(String spaceUuid, Pattern excludeNames) throws
            CloudFoundryException {
        log.debug("listApplications");
        try {
            ListApplicationsResponse response = this.cfClient
                    .applicationsV2()
                    .list(ListApplicationsRequest.builder()
                            .spaceId(spaceUuid)
                            .build())
                    .get(Config.CF_API_TIMEOUT);
            return response.getResources().stream().filter(
                    cloudApplication -> (
                            spaceUuid == null
                                    || spaceUuid.equals(cloudApplication.getEntity().getSpaceId()))
                            && (
                            excludeNames == null
                                    || !excludeNames.matcher(cloudApplication.getEntity().getName()).matches()))
                    .map(
                            cloudApplication ->
                                    ApplicationIdentity.builder()
                                            .guid(cloudApplication.getMetadata().getId())
                                            .name(cloudApplication.getEntity().getName())
                                            .build())
                    .collect(Collectors.toList());
        } catch (RuntimeException r) {
            throw new CloudFoundryException(r);
        }
    }

    @Override
    public List<String> listRouteApplications(String routeUuid) throws CloudFoundryException {
        log.debug("listRouteApplications");
        try {
            ListRouteApplicationsResponse response = cfClient.routes()
                    .listApplications(
                            ListRouteApplicationsRequest.builder()
                                    .routeId(routeUuid)
                                    .build())
                    .get(Config.CF_API_TIMEOUT);
            return response.getResources().stream()
                    .map(appResource -> appResource.getMetadata().getId())
                    .collect(Collectors.toList());
        } catch (RuntimeException r) {
            throw new CloudFoundryException(r);
        }
    }

    @Override
    public boolean startApplication(String applicationUuid) throws CloudFoundryException {
        log.debug("startApplication");
        return changeApplicationState(applicationUuid, CloudFoundryAppState.STARTED);
    }

    @Override
    public boolean stopApplication(String applicationUuid) throws CloudFoundryException {
        log.debug("stopApplication");
        return changeApplicationState(applicationUuid, CloudFoundryAppState.STOPPED);
    }

    @Override
    public void unbind(String bindingId) throws CloudFoundryException {
        cfClient.serviceBindings()
                .delete(DeleteServiceBindingRequest.builder()
                        .serviceBindingId(bindingId)
                        .build())
                .get(Config.CF_API_TIMEOUT);

    }

}