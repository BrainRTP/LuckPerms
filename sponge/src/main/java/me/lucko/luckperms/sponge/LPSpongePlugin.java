/*
 * This file is part of LuckPerms, licensed under the MIT License.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.luckperms.sponge;

import lombok.Getter;

import com.google.inject.Inject;

import me.lucko.luckperms.api.Contexts;
import me.lucko.luckperms.api.LuckPermsApi;
import me.lucko.luckperms.api.PlatformType;
import me.lucko.luckperms.common.actionlog.LogDispatcher;
import me.lucko.luckperms.common.api.ApiHandler;
import me.lucko.luckperms.common.api.ApiProvider;
import me.lucko.luckperms.common.backup.ImporterSender;
import me.lucko.luckperms.common.buffers.BufferedRequest;
import me.lucko.luckperms.common.buffers.UpdateTaskBuffer;
import me.lucko.luckperms.common.caching.handlers.CachedStateManager;
import me.lucko.luckperms.common.calculators.CalculatorFactory;
import me.lucko.luckperms.common.commands.abstraction.Command;
import me.lucko.luckperms.common.commands.sender.Sender;
import me.lucko.luckperms.common.config.ConfigKeys;
import me.lucko.luckperms.common.config.LuckPermsConfiguration;
import me.lucko.luckperms.common.constants.CommandPermission;
import me.lucko.luckperms.common.constants.Constants;
import me.lucko.luckperms.common.contexts.ContextManager;
import me.lucko.luckperms.common.contexts.LuckPermsCalculator;
import me.lucko.luckperms.common.dependencies.DependencyManager;
import me.lucko.luckperms.common.locale.LocaleManager;
import me.lucko.luckperms.common.locale.NoopLocaleManager;
import me.lucko.luckperms.common.locale.SimpleLocaleManager;
import me.lucko.luckperms.common.logging.SenderLogger;
import me.lucko.luckperms.common.managers.GenericTrackManager;
import me.lucko.luckperms.common.managers.TrackManager;
import me.lucko.luckperms.common.messaging.InternalMessagingService;
import me.lucko.luckperms.common.model.User;
import me.lucko.luckperms.common.plugin.LuckPermsPlugin;
import me.lucko.luckperms.common.plugin.LuckPermsScheduler;
import me.lucko.luckperms.common.storage.Storage;
import me.lucko.luckperms.common.storage.StorageFactory;
import me.lucko.luckperms.common.storage.StorageType;
import me.lucko.luckperms.common.storage.backing.file.FileWatcher;
import me.lucko.luckperms.common.tasks.CacheHousekeepingTask;
import me.lucko.luckperms.common.tasks.ExpireTemporaryTask;
import me.lucko.luckperms.common.tasks.UpdateTask;
import me.lucko.luckperms.common.treeview.PermissionVault;
import me.lucko.luckperms.common.utils.UuidCache;
import me.lucko.luckperms.common.verbose.VerboseHandler;
import me.lucko.luckperms.sponge.calculators.SpongeCalculatorFactory;
import me.lucko.luckperms.sponge.commands.SpongeMainCommand;
import me.lucko.luckperms.sponge.contexts.SpongeContextManager;
import me.lucko.luckperms.sponge.contexts.WorldCalculator;
import me.lucko.luckperms.sponge.managers.SpongeGroupManager;
import me.lucko.luckperms.sponge.managers.SpongeUserManager;
import me.lucko.luckperms.sponge.messaging.SpongeMessagingFactory;
import me.lucko.luckperms.sponge.service.LuckPermsService;
import me.lucko.luckperms.sponge.service.model.LPPermissionService;
import me.lucko.luckperms.sponge.service.model.LPSubject;
import me.lucko.luckperms.sponge.service.model.LPSubjectCollection;
import me.lucko.luckperms.sponge.service.persisted.PersistedCollection;
import me.lucko.luckperms.sponge.tasks.ServiceCacheHousekeepingTask;
import me.lucko.luckperms.sponge.utils.VersionData;

import org.slf4j.Logger;
import org.spongepowered.api.Game;
import org.spongepowered.api.Platform;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.CommandManager;
import org.spongepowered.api.config.ConfigDir;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.Order;
import org.spongepowered.api.event.game.state.GamePostInitializationEvent;
import org.spongepowered.api.event.game.state.GamePreInitializationEvent;
import org.spongepowered.api.event.game.state.GameStoppingServerEvent;
import org.spongepowered.api.plugin.Plugin;
import org.spongepowered.api.profile.GameProfile;
import org.spongepowered.api.scheduler.AsynchronousExecutor;
import org.spongepowered.api.scheduler.Scheduler;
import org.spongepowered.api.scheduler.SpongeExecutorService;
import org.spongepowered.api.scheduler.SynchronousExecutor;
import org.spongepowered.api.service.permission.PermissionService;
import org.spongepowered.api.service.permission.Subject;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * LuckPerms implementation for the Sponge API.
 */
@Getter
@Plugin(
        id = "luckperms",
        name = "LuckPerms",
        version = VersionData.VERSION,
        authors = {"Luck"},
        description = "A permissions plugin",
        url = "https://github.com/lucko/LuckPerms"
)
public class LPSpongePlugin implements LuckPermsPlugin {

    @Inject
    private Logger logger;

    @Inject
    private Game game;

    @Inject
    @ConfigDir(sharedRoot = false)
    private Path configDir;

    private Scheduler spongeScheduler = Sponge.getScheduler();

    @Inject
    @SynchronousExecutor
    private SpongeExecutorService syncExecutorService;

    @Inject
    @AsynchronousExecutor
    private SpongeExecutorService asyncExecutorService;

    private boolean lateLoad = false;
    private long startTime;

    private LuckPermsScheduler scheduler;
    private SpongeCommand commandManager;
    private LuckPermsConfiguration configuration;
    private SpongeUserManager userManager;
    private SpongeGroupManager groupManager;
    private TrackManager trackManager;
    private Storage storage;
    private FileWatcher fileWatcher = null;
    private InternalMessagingService messagingService = null;
    private UuidCache uuidCache;
    private ApiProvider apiProvider;
    private me.lucko.luckperms.api.Logger log;
    private LuckPermsService service;
    private LocaleManager localeManager;
    private CachedStateManager cachedStateManager;
    private ContextManager<Subject> contextManager;
    private CalculatorFactory calculatorFactory;
    private BufferedRequest<Void> updateTaskBuffer;
    private VerboseHandler verboseHandler;
    private SpongeSenderFactory senderFactory;
    private PermissionVault permissionVault;
    private LogDispatcher logDispatcher;
    private Set<UUID> uniqueConnections = ConcurrentHashMap.newKeySet();

    @Listener(order = Order.FIRST)
    public void onEnable(GamePreInitializationEvent event) {
        startTime = System.currentTimeMillis();
        scheduler = new LPSpongeScheduler(this);
        localeManager = new NoopLocaleManager();
        senderFactory = new SpongeSenderFactory(this);
        log = new SenderLogger(this, getConsoleSender());

        LuckPermsPlugin.sendStartupBanner(getConsoleSender(), this);
        verboseHandler = new VerboseHandler(scheduler.async(), getVersion());
        permissionVault = new PermissionVault(scheduler.async());
        logDispatcher = new LogDispatcher(this);

        getLog().info("Loading configuration...");
        configuration = new SpongeConfig(this);
        configuration.init();
        configuration.loadAll();

        Set<StorageType> storageTypes = StorageFactory.getRequiredTypes(this, StorageType.H2);
        DependencyManager.loadStorageDependencies(this, storageTypes);

        // register events
        game.getEventManager().registerListeners(this, new SpongeListener(this));

        if (getConfiguration().get(ConfigKeys.WATCH_FILES)) {
            fileWatcher = new FileWatcher(this);
            getScheduler().asyncRepeating(fileWatcher, 30L);
        }

        // initialise datastore
        storage = StorageFactory.getInstance(this, StorageType.H2);

        // initialise messaging
        messagingService = new SpongeMessagingFactory(this).getInstance();

        // setup the update task buffer
        updateTaskBuffer = new UpdateTaskBuffer(this);

        // load locale
        localeManager = new SimpleLocaleManager();
        localeManager.tryLoad(this, new File(getDataDirectory(), "lang.yml"));

        // register commands
        CommandManager cmdService = game.getCommandManager();
        commandManager = new SpongeCommand(this);
        cmdService.register(this, commandManager, "luckperms", "lp", "perm", "perms", "permission", "permissions");

        // load internal managers
        getLog().info("Loading internal permission managers...");
        uuidCache = new UuidCache(this);
        userManager = new SpongeUserManager(this);
        groupManager = new SpongeGroupManager(this);
        trackManager = new GenericTrackManager(this);
        calculatorFactory = new SpongeCalculatorFactory(this);
        cachedStateManager = new CachedStateManager();

        // setup context manager
        contextManager = new SpongeContextManager(this);
        contextManager.registerCalculator(new WorldCalculator(this));
        contextManager.registerCalculator(new LuckPermsCalculator<>(getConfiguration()), true);

        // register the PermissionService with Sponge
        getLog().info("Registering PermissionService...");
        service = new LuckPermsService(this);

        if (game.getPluginManager().getPlugin("permissionsex").isPresent()) {
            getLog().warn("Detected PermissionsEx - assuming it's loaded for migration.");
            getLog().warn("Delaying LuckPerms PermissionService registration.");
            lateLoad = true;
        } else {
            game.getServiceManager().setProvider(this, LPPermissionService.class, service);
            game.getServiceManager().setProvider(this, PermissionService.class, service.sponge());
            game.getServiceManager().setProvider(this, LuckPermsService.class, service);
        }

        // register with the LP API
        apiProvider = new ApiProvider(this);
        ApiHandler.registerProvider(apiProvider);
        game.getServiceManager().setProvider(this, LuckPermsApi.class, apiProvider);

        // schedule update tasks
        int mins = getConfiguration().get(ConfigKeys.SYNC_TIME);
        if (mins > 0) {
            long ticks = mins * 60 * 20;
            scheduler.asyncRepeating(() -> updateTaskBuffer.request(), ticks);
        }
        scheduler.asyncLater(() -> updateTaskBuffer.request(), 40L);

        // run an update instantly.
        getLog().info("Performing initial data load...");
        new UpdateTask(this, true).run();

        // register tasks
        scheduler.asyncRepeating(new ExpireTemporaryTask(this), 60L);
        scheduler.asyncRepeating(new CacheHousekeepingTask(this), 2400L);
        scheduler.asyncRepeating(new ServiceCacheHousekeepingTask(service), 2400L);

        getLog().info("Successfully enabled. (took " + (System.currentTimeMillis() - startTime) + "ms)");
    }

    @Listener(order = Order.LATE)
    public void onLateEnable(GamePreInitializationEvent event) {
        if (lateLoad) {
            getLog().info("Providing late registration of PermissionService...");
            game.getServiceManager().setProvider(this, LPPermissionService.class, service);
            game.getServiceManager().setProvider(this, PermissionService.class, service.sponge());
            game.getServiceManager().setProvider(this, LuckPermsService.class, service);
        }
    }

    @Listener
    public void onDisable(GameStoppingServerEvent event) {
        getLog().info("Closing storage...");
        storage.shutdown();

        if (fileWatcher != null) {
            fileWatcher.close();
        }

        if (messagingService != null) {
            getLog().info("Closing messaging service...");
            messagingService.close();
        }

        ApiHandler.unregisterProvider();

        getLog().info("Shutting down internal scheduler...");
        scheduler.shutdown();
    }

    @Listener
    public void onPostInit(GamePostInitializationEvent event) {
        // register permissions
        LuckPermsService service = this.service;
        if (service == null) {
            return;
        }

        for (CommandPermission perm : CommandPermission.values()) {
            registerPermission(service, perm.getPermission());
        }
    }

    @Override
    public void onPostUpdate() {
        for (LPSubjectCollection collection : service.getLoadedCollections().values()) {
            if (collection instanceof PersistedCollection) {
                ((PersistedCollection) collection).loadAll();
            }
        }
        service.invalidateAllCaches(LPSubject.CacheLevel.PARENT);
    }

    @Override
    public File getDataDirectory() {
        File base = configDir.toFile().getParentFile().getParentFile();
        File luckPermsDir = new File(base, "luckperms");
        luckPermsDir.mkdirs();
        return luckPermsDir;
    }

    @Override
    public File getConfigDirectory() {
        return configDir.toFile();
    }

    @Override
    public InputStream getResourceStream(String path) {
        return getClass().getClassLoader().getResourceAsStream(path);
    }

    @Override
    public Player getPlayer(User user) {
        if (!game.isServerAvailable()) {
            return null;
        }

        return game.getServer().getPlayer(uuidCache.getExternalUUID(user.getUuid())).orElse(null);
    }

    @Override
    public Optional<UUID> lookupUuid(String username) {
        if (!game.isServerAvailable()) {
            return Optional.empty();
        }

        CompletableFuture<GameProfile> fut = game.getServer().getGameProfileManager().get(username);
        try {
            return Optional.of(fut.get().getUniqueId());
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
            return Optional.empty();
        }
    }

    @Override
    public Contexts getContextForUser(User user) {
        Player player = getPlayer(user);
        if (player == null) {
            return null;
        }
        return contextManager.getApplicableContexts(player);
    }

    @Override
    public String getVersion() {
        return VersionData.VERSION;
    }

    @Override
    public PlatformType getServerType() {
        return PlatformType.SPONGE;
    }

    @Override
    public String getServerName() {
        return getGame().getPlatform().getContainer(Platform.Component.IMPLEMENTATION).getName();
    }

    @Override
    public String getServerVersion() {
        return getGame().getPlatform().getContainer(Platform.Component.API).getName() + ": " +
                getGame().getPlatform().getContainer(Platform.Component.API).getVersion().orElse("null") + " - " +
                getGame().getPlatform().getContainer(Platform.Component.IMPLEMENTATION).getName() + ": " +
                getGame().getPlatform().getContainer(Platform.Component.IMPLEMENTATION).getVersion().orElse("null");
    }

    @Override
    public int getPlayerCount() {
        return game.isServerAvailable() ? game.getServer().getOnlinePlayers().size() : 0;
    }

    @Override
    public List<String> getPlayerList() {
        return game.isServerAvailable() ? game.getServer().getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList()) : new ArrayList<>();
    }

    @Override
    public Set<UUID> getOnlinePlayers() {
        return game.isServerAvailable() ? game.getServer().getOnlinePlayers().stream().map(Player::getUniqueId).collect(Collectors.toSet()) : new HashSet<>();
    }

    @Override
    public boolean isPlayerOnline(UUID external) {
        return game.isServerAvailable() ? game.getServer().getPlayer(external).map(Player::isOnline).orElse(false) : false;
    }

    @Override
    public List<Sender> getOnlineSenders() {
        if (!game.isServerAvailable()) {
            return new ArrayList<>();
        }

        return game.getServer().getOnlinePlayers().stream()
                .map(s -> getSenderFactory().wrap(s))
                .collect(Collectors.toList());
    }

    @Override
    public Sender getConsoleSender() {
        if (!game.isServerAvailable()) {
            return new ImporterSender(this) {

                @Override
                public String getName() {
                    return Constants.CONSOLE_NAME;
                }

                @Override
                public UUID getUuid() {
                    return Constants.CONSOLE_UUID;
                }

                @Override
                public boolean isImport() {
                    return false;
                }

                @Override
                protected void consumeMessage(String s) {
                    logger.info(s);
                }
            };
        }
        return getSenderFactory().wrap(game.getServer().getConsole());
    }

    @Override
    public Set<Contexts> getPreProcessContexts(boolean op) {
        return Collections.emptySet();
    }

    @Override
    public List<Command> getExtraCommands() {
        return Collections.singletonList(new SpongeMainCommand(this));
    }

    @Override
    public LinkedHashMap<String, Object> getExtraInfo() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("SubjectCollection count", service.getLoadedCollections().size());
        map.put("Subject count",
                service.getLoadedCollections().values().stream()
                        .map(LPSubjectCollection::getLoadedSubjects)
                        .mapToInt(AbstractCollection::size)
                        .sum()
        );
        map.put("PermissionDescription count", service.getDescriptions().size());
        return map;
    }

    private void registerPermission(LuckPermsService p, String node) {
        p.registerPermissionDescription(node, null, game.getPluginManager().fromInstance(this).get());
    }
}
