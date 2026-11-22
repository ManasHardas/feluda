package org.neighborhoodwatchdog.android.di

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.android.asCoroutineDispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.neighborhoodwatchdog.android.config.AppConfig
import org.neighborhoodwatchdog.android.data.api.AuthApi
import org.neighborhoodwatchdog.android.data.api.WatchdogApi
import org.neighborhoodwatchdog.android.data.auth.ApiKeyInterceptor
import org.neighborhoodwatchdog.android.data.auth.AuthRepository
import org.neighborhoodwatchdog.android.data.auth.BearerInterceptor
import org.neighborhoodwatchdog.android.data.auth.EncryptedFileStore
import org.neighborhoodwatchdog.android.data.auth.EncryptedTokenStore
import org.neighborhoodwatchdog.android.data.auth.KeystoreCipher
import org.neighborhoodwatchdog.android.data.auth.RemoteTokenRefresher
import org.neighborhoodwatchdog.android.data.auth.TokenAuthenticator
import org.neighborhoodwatchdog.android.data.local.EventDao
import org.neighborhoodwatchdog.android.data.local.FingerprintStore
import org.neighborhoodwatchdog.android.data.local.LocalState
import org.neighborhoodwatchdog.android.data.local.RoomEventQueueStore
import org.neighborhoodwatchdog.android.data.local.RoomSentEventLog
import org.neighborhoodwatchdog.android.data.local.WatchdogDatabase
import org.neighborhoodwatchdog.android.data.repository.DeviceRepository
import org.neighborhoodwatchdog.android.data.repository.WatchdogRepository
import org.neighborhoodwatchdog.android.domain.auth.TokenManager
import org.neighborhoodwatchdog.android.domain.auth.TokenStore
import org.neighborhoodwatchdog.android.domain.model.Clock
import org.neighborhoodwatchdog.android.domain.model.WatchdogJson
import org.neighborhoodwatchdog.android.domain.reporting.EventFactory
import org.neighborhoodwatchdog.android.domain.reporting.EventQueueStore
import org.neighborhoodwatchdog.android.domain.reporting.EventTransport
import org.neighborhoodwatchdog.android.domain.reporting.EventUploader
import org.neighborhoodwatchdog.android.domain.reporting.SentEventLog
import org.neighborhoodwatchdog.android.monitoring.AliveClock
import org.neighborhoodwatchdog.android.monitoring.CellularNetworkProvider
import org.neighborhoodwatchdog.android.monitoring.ConnectivityMonitor
import org.neighborhoodwatchdog.android.monitoring.HomeNetworkManager
import org.neighborhoodwatchdog.android.monitoring.MonitoringController
import org.neighborhoodwatchdog.android.monitoring.NetworkEventTransport
import org.neighborhoodwatchdog.android.monitoring.PermissionStatus
import org.neighborhoodwatchdog.android.monitoring.PowerTracker
import org.neighborhoodwatchdog.android.monitoring.ProbeRunner
import org.neighborhoodwatchdog.android.monitoring.WorkScheduler
import org.neighborhoodwatchdog.android.push.PushManager
import org.neighborhoodwatchdog.android.session.SessionCoordinator
import org.neighborhoodwatchdog.android.session.SessionSignal
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    private val jsonMediaType = "application/json".toMediaType()

    @Provides @Singleton
    fun config(): AppConfig = AppConfig.fromBuildConfig()

    @Provides @Singleton
    fun clock(): Clock = Clock { System.currentTimeMillis() }

    @Provides @Singleton
    fun localState(@ApplicationContext context: Context): LocalState = LocalState(context)

    @Provides @Singleton
    fun sessionSignal(): SessionSignal = SessionSignal()

    // ---- secure storage and auth ----

    @Provides @Singleton
    fun encryptedFiles(@ApplicationContext context: Context): EncryptedFileStore = EncryptedFileStore(context, KeystoreCipher())

    @Provides @Singleton
    fun tokenStore(files: EncryptedFileStore): TokenStore = EncryptedTokenStore(files)

    @Provides @Singleton
    fun fingerprintStore(files: EncryptedFileStore): FingerprintStore = FingerprintStore(files)

    @Provides @Singleton @Named("base")
    fun baseClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    @Provides @Singleton
    fun authApi(@Named("base") base: OkHttpClient, config: AppConfig): AuthApi = Retrofit.Builder()
        .baseUrl(config.authRoot)
        .client(base.newBuilder().addInterceptor(ApiKeyInterceptor(config.authPublicKey)).build())
        .addConverterFactory(WatchdogJson.asConverterFactory(jsonMediaType))
        .build()
        .create(AuthApi::class.java)

    @Provides @Singleton
    fun tokenManager(store: TokenStore, authApi: AuthApi, clock: Clock, signal: SessionSignal): TokenManager =
        TokenManager(store, RemoteTokenRefresher(authApi, clock), clock, onSessionEnded = signal::bump)

    @Provides @Singleton
    fun authRepository(authApi: AuthApi, tokens: TokenManager, clock: Clock): AuthRepository = AuthRepository(authApi, tokens, clock)

    @Provides @Singleton @Named("api")
    fun apiClient(@Named("base") base: OkHttpClient, tokens: TokenManager): OkHttpClient = base.newBuilder()
        .addInterceptor(BearerInterceptor(tokens))
        .authenticator(TokenAuthenticator(tokens))
        .build()

    @Provides @Singleton
    fun watchdogApi(@Named("api") client: OkHttpClient, config: AppConfig): WatchdogApi = Retrofit.Builder()
        .baseUrl(config.apiRoot)
        .client(client)
        .addConverterFactory(WatchdogJson.asConverterFactory(jsonMediaType))
        .build()
        .create(WatchdogApi::class.java)

    @Provides @Singleton
    fun repository(api: WatchdogApi, localState: LocalState): WatchdogRepository = WatchdogRepository(api, localState)

    @Provides @Singleton
    fun deviceRepository(@ApplicationContext context: Context, api: WatchdogApi, localState: LocalState, config: AppConfig): DeviceRepository =
        DeviceRepository(context, api, localState, config)

    // ---- durable queue ----

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): WatchdogDatabase =
        Room.databaseBuilder(context, WatchdogDatabase::class.java, "watchdog.db").build()

    @Provides
    fun eventDao(database: WatchdogDatabase): EventDao = database.eventDao()

    @Provides @Singleton
    fun queueStore(dao: EventDao): EventQueueStore = RoomEventQueueStore(dao)

    @Provides @Singleton
    fun sentLog(dao: EventDao): SentEventLog = RoomSentEventLog(dao)

    @Provides @Singleton
    fun cellularProvider(@ApplicationContext context: Context): CellularNetworkProvider = CellularNetworkProvider(context)

    @Provides @Singleton
    fun eventTransport(
        @Named("api") client: OkHttpClient,
        cellular: CellularNetworkProvider,
        config: AppConfig,
        localState: LocalState,
    ): EventTransport = NetworkEventTransport(client, cellular, config, localState)

    @Provides @Singleton
    fun uploader(store: EventQueueStore, sentLog: SentEventLog, transport: EventTransport, clock: Clock, localState: LocalState): EventUploader =
        EventUploader(store, sentLog, transport, clock, onDelivered = { localState.recordReport(it) })

    @Provides @Singleton
    fun eventFactory(localState: LocalState, config: AppConfig): EventFactory =
        EventFactory(sequence = localState, osVersion = config.osVersion, appVersion = config.appVersion)

    // ---- monitoring ----

    @Provides @Singleton @Named("monitor")
    fun monitorHandler(): Handler = Handler(HandlerThread("watchdog-monitor").apply { start() }.looper)

    @Provides @Singleton
    fun permissionStatus(@ApplicationContext context: Context): PermissionStatus = PermissionStatus(context)

    @Provides @Singleton
    fun workScheduler(@ApplicationContext context: Context): WorkScheduler = WorkScheduler(context)

    @Provides @Singleton
    fun homeNetworkManager(@ApplicationContext context: Context, store: FingerprintStore): HomeNetworkManager =
        HomeNetworkManager(context, store)

    @Provides @Singleton
    fun monitoringController(
        @ApplicationContext context: Context,
        @Named("monitor") handler: Handler,
        @Named("base") base: OkHttpClient,
        config: AppConfig,
        uploader: EventUploader,
        eventFactory: EventFactory,
        queueStore: EventQueueStore,
        sentLog: SentEventLog,
        fingerprintStore: FingerprintStore,
        localState: LocalState,
        tokens: TokenManager,
        permissions: PermissionStatus,
        scheduler: WorkScheduler,
        devices: DeviceRepository,
        clock: Clock,
    ): MonitoringController = MonitoringController(
        monitor = ConnectivityMonitor(context, handler, fingerprintStore),
        monitorDispatcher = handler.asCoroutineDispatcher("watchdog-monitor"),
        probes = ProbeRunner(base, config),
        uploader = uploader,
        eventFactory = eventFactory,
        queueStore = queueStore,
        sentLog = sentLog,
        fingerprintStore = fingerprintStore,
        localState = localState,
        tokens = tokens,
        powerTracker = PowerTracker(context, clock),
        aliveClock = AliveClock(context, clock),
        permissions = permissions,
        scheduler = scheduler,
        devices = devices,
        clock = clock,
    )

    @Provides @Singleton
    fun pushManager(
        @ApplicationContext context: Context,
        config: AppConfig,
        devices: DeviceRepository,
        localState: LocalState,
        auth: AuthRepository,
    ): PushManager = PushManager(context, config, devices, localState, auth)

    @Provides @Singleton
    fun sessionCoordinator(
        auth: AuthRepository,
        repository: WatchdogRepository,
        controller: MonitoringController,
        localState: LocalState,
        signal: SessionSignal,
    ): SessionCoordinator = SessionCoordinator(auth, repository, controller, localState, signal)
}
