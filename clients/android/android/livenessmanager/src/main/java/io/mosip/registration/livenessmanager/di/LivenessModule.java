/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.livenessmanager.di;

import android.app.Application;
import android.content.Context;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;
import io.mosip.liveness.core.audit.AuditSink;
import io.mosip.liveness.core.config.LivenessConfigKeys;
import io.mosip.registration.clientmanager.config.SessionManager;
import io.mosip.registration.clientmanager.repository.GlobalParamRepository;
import io.mosip.registration.clientmanager.repository.UserBiometricRepository;
import io.mosip.registration.clientmanager.service.LoginService;
import io.mosip.registration.clientmanager.spi.AuditManagerService;
import io.mosip.registration.livenessmanager.DefaultLivenessService;
import io.mosip.registration.livenessmanager.LivenessRuntime;
import io.mosip.registration.livenessmanager.LivenessService;
import io.mosip.registration.livenessmanager.audit.ArcAuditSink;
import io.mosip.registration.livenessmanager.auth.ArcOperatorDirectory;
import io.mosip.registration.livenessmanager.auth.FaceAuthenticator;
import io.mosip.registration.livenessmanager.config.GlobalParamConfigSource;
import io.mosip.registration.livenessmanager.match.EngineFaceVerifier;
import io.mosip.registration.livenessmanager.match.FaceVerifier;

/**
 * Dagger module of face liveness: {@link LivenessService} for resident capture, operator and supervisor
 * authentication, backed by ARC's {@link GlobalParamRepository} (configuration) and {@link AuditManagerService}
 * (audits). ARC passes the application when it builds its {@code AppComponent}
 * ({@code .livenessModule(new LivenessModule(getApplication()))}), because ARC binds {@code Application} and
 * {@code Context} twice already.
 */
@Module
public class LivenessModule {

    private final Application application;

    /**
     * Creates the module.
     *
     * @param application the application
     */
    public LivenessModule(Application application) {
        this.application = application;
    }

    /**
     * Provides the configuration source over ARC's global parameters.
     *
     * @param repository ARC's repository
     * @return the source
     */
    @Provides
    @Singleton
    GlobalParamConfigSource provideLivenessConfig(GlobalParamRepository repository) {
        return new GlobalParamConfigSource(repository);
    }

    /**
     * Provides the audit sink over ARC's audit service.
     *
     * @param auditManagerService ARC's audit service
     * @return the sink
     */
    @Provides
    @Singleton
    AuditSink provideLivenessAudit(AuditManagerService auditManagerService) {
        return new ArcAuditSink(auditManagerService);
    }

    /**
     * Installs the process-wide runtime the capture screen uses.
     *
     * @param config       configuration source
     * @param audit        audit sink
     * @param loginService ARC's login service (roles)
     * @return the runtime
     */
    @Provides
    @Singleton
    LivenessRuntime provideLivenessRuntime(GlobalParamConfigSource config, AuditSink audit,
                                           LoginService loginService) {
        LivenessRuntime runtime = LivenessRuntime.install(application, config, audit);
        // Roles of the signed-in user for the model import (supervisor or admin only).
        runtime.setRolesLookup(loginService::getRolesByUserId);
        // The signed-in user as SessionManager keeps it (audit REG-EVT-LV17, supervisor override prompt).
        runtime.setUserIdSupplier(() -> application.getSharedPreferences(
                application.getString(io.mosip.registration.clientmanager.R.string.app_name), Context.MODE_PRIVATE)
                .getString(SessionManager.USER_ID, ""));
        return runtime;
    }

    /**
     * Provides the service. When liveness is switched on, the models start loading in the background so the
     * first capture does not wait for them.
     *
     * @param runtime the runtime
     * @return the service
     */
    @Provides
    @Singleton
    LivenessService provideLivenessService(LivenessRuntime runtime) {
        LivenessService service = new DefaultLivenessService(runtime);
        boolean enabled = LivenessConfigKeys.YES.equalsIgnoreCase(
                runtime.config().get(LivenessConfigKeys.ENABLE).orElse(LivenessConfigKeys.DEFAULT_ENABLE));
        if (enabled) {
            service.preload();
        }
        return service;
    }

    /**
     * Provides the one-to-one face matcher over the engine's own YuNet and SFace models.
     *
     * @param runtime the runtime
     * @return the verifier
     */
    @Provides
    @Singleton
    FaceVerifier provideFaceVerifier(LivenessRuntime runtime) {
        return EngineFaceVerifier.over(runtime.engines(), runtime.decoder(), runtime.config());
    }

    /**
     * Provides operator and supervisor face authentication over ARC's users, roles and onboarding faces.
     *
     * @param service      the liveness service
     * @param loginService ARC's login service (roles, lockout)
     * @param biometrics   ARC's operator biometric repository
     * @param verifier     the face matcher
     * @return the authenticator
     */
    @Provides
    @Singleton
    FaceAuthenticator provideFaceAuthenticator(LivenessService service, LoginService loginService,
                                               UserBiometricRepository biometrics, FaceVerifier verifier) {
        return new FaceAuthenticator(service, new ArcOperatorDirectory(loginService, biometrics), verifier);
    }
}
