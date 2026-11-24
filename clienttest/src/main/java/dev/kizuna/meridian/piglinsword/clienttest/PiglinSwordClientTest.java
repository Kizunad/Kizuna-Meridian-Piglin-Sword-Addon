package dev.kizuna.meridian.piglinsword.clienttest;

import com.mojang.blaze3d.platform.NativeImage;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BlockedAttackSource;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastOutcome;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HorizontalDirection;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.server.TechniqueService.BlockedAttackObservation;
import dev.kizuna.meridian.technique.server.TechniqueState;
import dev.kizuna.meridian.vanilla.fabric.BlockedAttackBridge;
import io.wispforest.owo.ui.container.ScrollContainer;
import io.wispforest.owo.ui.core.ParentUIComponent;
import io.wispforest.owo.ui.core.UIComponent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.IntStream;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.CameraType;
import net.minecraft.client.Screenshot;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.illager.Vindicator;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.loader.api.FabricLoader;

/** P0 客户端证据，截图功法谱、卷轴和两种持剑施放反馈。 */
public final class PiglinSwordClientTest implements FabricClientGameTest {
    private static final String CLIENT_TEST_CLIP =
            System.getProperty("meridian.piglin.sword.clientTestClip", "p0");
    private static final int WINDOW_WIDTH = 1280;
    private static final int WINDOW_HEIGHT = 960;
    private static final int SETTLE_TICKS = 12;
    private static final int CAST_FRAME_COUNT = 6;
    private static final int FRAME_INTERVAL_TICKS = 2;
    private static final int COOLDOWN_TICKS = 70;
    private static final String PREFIX = "meridian_piglin_sword:";
    private static final String UPPER_SCROLL = PREFIX + "upper_scroll";
    private static final String CODEX = "meridian_technique:technique_codex";
    private static final String MOLTEN_SWORD = PREFIX + "molten_sword";
    private static final int P2_PRE_FRAMES = 5;
    private static final int P2_POST_CAST_FRAMES = 20;
    private static final int P2_BLOCK_VISIBILITY_TICKS = 2;
    private static final int P2_SPRING_BACK_COOLDOWN_TICKS = 120;
    private static final int P2_WALL_X_OFFSET = 1;
    private static final double P2_CASTER_X = 0.0D;
    private static final double P2_PLAYER_X = 0.0D;
    private static final double P2_PLAYER_Z = 0.0D;
    private static final double P2_DEFENDER_X = 2.5D;
    private static final double P2_TARGET_X = 4.0D;
    private static final double P2_CAMERA_Z = -5.0D;
    private static final double P2_CAMERA_Y = 2.8D;
    private static final double P2_ENTITY_AREA_RADIUS_X = 8.0D;
    private static final double P2_ENTITY_AREA_BELOW = 2.0D;
    private static final double P2_ENTITY_AREA_ABOVE = 5.0D;
    private static final double P2_ENTITY_AREA_RADIUS_Z = 4.0D;
    private static final float P2_PLAYER_YAW = -90.0F;
    private static final float P2_DEFENDER_YAW = 90.0F;
    private static final float P2_TARGET_YAW = 90.0F;
    private static final float P2_LEVEL_PITCH = 0.0F;
    private static final float P2_CAMERA_YAW = 0.0F;
    private static final float P2_CAMERA_PITCH = 8.0F;
    private static final long P2_BLOCKED_EVENT_AGE_MILLIS = 650L;
    private static final Path P2_SCREENSHOT_DIRECTORY = Path.of("screenshots");
    private static final String SPRING_BACK = PREFIX + "spring_back";

    @Override
    public void runTest(ClientGameTestContext context) {
        context.getInput().resizeWindow(WINDOW_WIDTH, WINDOW_HEIGHT);
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            if ("p2".equals(CLIENT_TEST_CLIP)) {
                runP2(context, world);
                return;
            }
            UUID playerId = prepare(context, world);
            captureScroll(context, world);
            captureCodex(context, world);
            Vindicator target = spawnTarget(world, playerId);
            captureWind(context, world, playerId, target);
            captureQuiver(context, world, playerId, target);
        }
    }

    private static void runP2(ClientGameTestContext context, TestSingleplayerContext world) {
        UUID playerId = prepare(context, world);
        P2Arena arena = prepareP2Arena(context, world, playerId, BlockedAttackSource.SHIELD);
        captureP2Segment(context, world, playerId, arena,
                new P2Segment("spring-back-shield", BlockedAttackSource.SHIELD));
        waitForCooldown(context);
        arena = prepareP2Arena(context, world, playerId, BlockedAttackSource.GUARD);
        captureP2Segment(context, world, playerId, arena,
                new P2Segment("spring-back-guard", BlockedAttackSource.GUARD));
        waitForCooldown(context);
        arena = prepareP2Arena(context, world, playerId, BlockedAttackSource.HARD_OBJECT);
        captureP2Segment(context, world, playerId, arena,
                new P2Segment("spring-back-hard-object", BlockedAttackSource.HARD_OBJECT));
        waitForCooldown(context);
        arena = prepareP2Arena(context, world, playerId, BlockedAttackSource.SHIELD);
        captureExpiredP2Segment(context, world, playerId, arena);
        context.waitTicks(SETTLE_TICKS);
    }

    private static P2Arena prepareP2Arena(ClientGameTestContext context, TestSingleplayerContext world,
                                          UUID playerId, BlockedAttackSource source) {
        world.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            player.setGameMode(GameType.SURVIVAL);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GOLDEN_SWORD));
            player.stopUsingItem();
            player.snapTo(P2_PLAYER_X, player.getY(), P2_PLAYER_Z, P2_PLAYER_YAW, P2_LEVEL_PITCH);
            clearP2Entities(server, player);
            BlockPos wall = p2Wall(player);
            server.overworld().setBlockAndUpdate(wall, Blocks.AIR.defaultBlockState());
            server.overworld().setBlockAndUpdate(wall.above(), Blocks.AIR.defaultBlockState());
        });
        P2Arena arena = world.getServer().computeOnServer(server -> spawnP2Arena(server, playerId, source));
        context.waitFor(client -> client.level.getEntity(arena.cameraEntityId()) != null);
        context.runOnClient(client -> {
            client.gui.setScreen(null);
            client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            Entity camera = client.level.getEntity(arena.cameraEntityId());
            if (camera == null) {
                throw new AssertionError("客户端没有收到弹式固定侧面机位");
            }
            client.setCameraEntity(camera);
        });
        clearClientPopups(context);
        context.waitTicks(SETTLE_TICKS);
        return arena;
    }

    private static void clearP2Entities(MinecraftServer server, ServerPlayer player) {
        Vec3 playerPosition = player.position();
        double minimumX = playerPosition.x - P2_ENTITY_AREA_RADIUS_X;
        double maximumX = playerPosition.x + P2_ENTITY_AREA_RADIUS_X;
        AABB area = new AABB(
                minimumX, playerPosition.y - P2_ENTITY_AREA_BELOW,
                playerPosition.z - P2_ENTITY_AREA_RADIUS_Z, maximumX,
                playerPosition.y + P2_ENTITY_AREA_ABOVE,
                playerPosition.z + P2_ENTITY_AREA_RADIUS_Z);
        server.overworld().getEntities((Entity) null, area, entity -> entity != player)
                .forEach(Entity::discard);
    }

    private static P2Arena spawnP2Arena(MinecraftServer server, UUID playerId, BlockedAttackSource source) {
        var level = server.overworld();
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        double groundY = player.getY();
        Vindicator defender = EntityTypes.VINDICATOR.create(level, EntitySpawnReason.COMMAND);
        Mob target = EntityTypes.COW.create(level, EntitySpawnReason.COMMAND);
        if (defender == null || target == null) {
            throw new IllegalStateException("无法生成弹式录像实体");
        }
        defender.snapTo(P2_DEFENDER_X, groundY, P2_PLAYER_Z, P2_DEFENDER_YAW, P2_LEVEL_PITCH);
        defender.setNoAi(true /* aiDisabled */);
        defender.setSilent(true /* silent */);
        if (source == BlockedAttackSource.SHIELD) {
            defender.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
            defender.startUsingItem(InteractionHand.OFF_HAND);
        }
        target.snapTo(P2_TARGET_X, groundY, P2_PLAYER_Z, P2_TARGET_YAW, P2_LEVEL_PITCH);
        target.setNoAi(true /* aiDisabled */);
        target.setSilent(true /* silent */);
        ArmorStand caster = new ArmorStand(level, P2_CASTER_X, groundY, P2_PLAYER_Z);
        caster.setNoGravity(true /* noGravity */);
        caster.setShowArms(true /* showArms */);
        caster.setNoBasePlate(true /* noBasePlate */);
        caster.setYRot(P2_PLAYER_YAW);
        caster.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GOLDEN_SWORD));
        level.addFreshEntity(defender);
        level.addFreshEntity(target);
        level.addFreshEntity(caster);
        ArmorStand camera = new ArmorStand(level, P2_PLAYER_X, groundY + P2_CAMERA_Y, P2_CAMERA_Z);
        camera.setInvisible(true /* invisible */);
        camera.setNoGravity(true /* noGravity */);
        camera.setYRot(P2_CAMERA_YAW);
        camera.setXRot(P2_CAMERA_PITCH);
        level.addFreshEntity(camera);
        return new P2Arena(defender.getUUID(), target.getUUID(), camera.getId(), p2Wall(player));
    }

    private static BlockPos p2Wall(ServerPlayer player) {
        return player.blockPosition().offset(P2_WALL_X_OFFSET, 0, 0);
    }

    private static void captureP2Segment(ClientGameTestContext context, TestSingleplayerContext world,
                                         UUID playerId, P2Arena arena, P2Segment segment) {
        List<String> rows = new ArrayList<>();
        P2FrameCapture capture = new P2FrameCapture(context, world, arena, segment.name(), rows);
        captureP2Frames(capture, "before", P2_PRE_FRAMES);
        CastOutcome outcome = triggerP2BlockAndCast(context, world, playerId, arena, segment.source());
        captureP2Frames(capture, outcome.getFailure().name(), P2_POST_CAST_FRAMES);
        String record = "帧\t服务端 tick\t施放结果\t目标血量\n" + String.join("\n", rows) + "\n";
        writeP2Record(segment.name() + ".tsv", record);
        if (outcome.getFailure() != TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED) {
            throw new IllegalStateException("阻挡后弹施放失败：" + outcome);
        }
    }

    private static CastOutcome triggerP2BlockAndCast(ClientGameTestContext context,
                                                     TestSingleplayerContext world, UUID playerId,
                                                     P2Arena arena, BlockedAttackSource source) {
        if (source == BlockedAttackSource.SHIELD) {
            world.getServer().runOnServer(server -> attackShield(server, playerId, arena));
        } else if (source == BlockedAttackSource.GUARD) {
            recordGuardBlock(world, playerId);
        } else {
            world.getServer().runOnServer(server -> recordHardObject(server, playerId, arena));
            context.waitTicks(P2_BLOCK_VISIBILITY_TICKS);
        }
        CastOutcome outcome = castSpringBack(world, playerId);
        sendP2Prompt(world, playerId, sourcePrompt(source));
        showP2Prompt(context, sourcePrompt(source));
        return outcome;
    }

    private static void attackShield(MinecraftServer server, UUID playerId, P2Arena arena) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        Entity defender = server.overworld().getEntity(arena.defenderId());
        if (!(defender instanceof LivingEntity living)) {
            throw new IllegalStateException("盾挡段缺少防守者");
        }
        player.attack(living);
        if (!hasBlockedSource(player, BlockedAttackSource.SHIELD)) {
            throw new IllegalStateException("真实盾挡没有回传 SHIELD 事实");
        }
    }

    private static void recordGuardBlock(TestSingleplayerContext world, UUID playerId) {
        world.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            long blockedMillis = currentMillis(player);
            boolean recorded = TechniqueFabric.service().recordBlockedAttack(new BlockedAttackObservation(
                    playerId, "client-guard-" + UUID.randomUUID(), BlockedAttackSource.GUARD, blockedMillis));
            if (!recorded) {
                throw new IllegalStateException("前臂格挡事实没有写入");
            }
        });
    }

    private static void recordHardObject(MinecraftServer server, UUID playerId, P2Arena arena) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        BlockPos obstacleBase = arena.wall();
        BlockPos obstacleTop = obstacleBase.above();
        server.overworld().setBlockAndUpdate(obstacleBase, Blocks.STONE.defaultBlockState());
        server.overworld().setBlockAndUpdate(obstacleTop, Blocks.STONE.defaultBlockState());
        if (!BlockedAttackBridge.observeHardObject(player)) {
            throw new IllegalStateException("硬物挡没有回传 HARD_OBJECT 事实");
        }
    }

    private static CastOutcome castSpringBack(TestSingleplayerContext world, UUID playerId) {
        return world.getServer().computeOnServer(server -> {
            TechniqueReference reference = TechniqueReference.newBuilder()
                    .setId(SPRING_BACK)
                    .setVersion(1)
                    .build();
            HorizontalDirection direction = HorizontalDirection.newBuilder()
                    .setX(1)
                    .setZ(0)
                    .build();
            CastRequest request = CastRequest.newBuilder()
                    .setBodyId(playerId.toString())
                    .setTechnique(reference)
                    .setCastId("client-spring-back-" + UUID.randomUUID())
                    .setDirection(direction)
                    .build();
            return TechniqueFabric.service().cast(playerId, request);
        });
    }

    private static void captureExpiredP2Segment(ClientGameTestContext context, TestSingleplayerContext world,
                                                UUID playerId, P2Arena arena) {
        List<String> rows = new ArrayList<>();
        P2FrameCapture capture = new P2FrameCapture(
                context, world, arena, "spring-back-expired", rows);
        captureP2Frames(capture, "before", P2_PRE_FRAMES);
        world.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            long blockedMillis = currentMillis(player) - P2_BLOCKED_EVENT_AGE_MILLIS;
            TechniqueFabric.service().recordBlockedAttack(new BlockedAttackObservation(
                    playerId, "client-expired-" + UUID.randomUUID(), BlockedAttackSource.SHIELD, blockedMillis));
        });
        CastOutcome outcome = castSpringBack(world, playerId);
        world.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            player.sendSystemMessage(Component.literal("弹：阻挡窗口已过期"), true /* overlay */);
        });
        showP2Prompt(context, "弹：阻挡窗口已过期");
        captureP2Frames(capture, outcome.getFailure().name(), P2_POST_CAST_FRAMES);
        writeP2Record("spring-back-expired.tsv",
                "帧\t服务端 tick\t施放结果\t目标血量\n" + String.join("\n", rows) + "\n");
        if (outcome.getFailure() != TechniqueFailure.FOLLOW_UP_REQUIRED) {
            throw new IllegalStateException("超时弹没有拒绝：" + outcome);
        }
    }

    private static void captureP2Frames(P2FrameCapture capture, String state, int frameCount) {
        for (int frame = 0; frame < frameCount; frame++) {
            capture.context().waitTicks(1);
            long tick = capture.world().getServer().computeOnServer(server -> server.overworld().getGameTime());
            float health = capture.world().getServer().computeOnServer(server -> {
                Entity target = server.overworld().getEntity(capture.arena().targetId());
                return target instanceof LivingEntity living ? living.getHealth() : 0.0F;
            });
            int frameNumber = capture.rows().size() + 1;
            String fileName = String.format(Locale.ROOT, "%s_%03d.png", capture.name(), frameNumber);
            captureP2Frame(capture.context(), fileName);
            capture.rows().add(frameNumber + "\t" + tick + "\t" + state + "\t" + health);
        }
    }

    private static void captureP2Frame(ClientGameTestContext context, String fileName) {
        Path relativeFile = P2_SCREENSHOT_DIRECTORY.resolve(fileName);
        context.runOnClient(client -> Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(),
                image -> saveP2Capture(image, relativeFile)));
    }

    private static void saveP2Capture(NativeImage image, Path relativeFile) {
        Path file = FabricLoader.getInstance().getGameDir().resolve(relativeFile);
        try (image) {
            Files.createDirectories(file.getParent());
            image.writeToFile(file);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static void writeP2Record(String name, String content) {
        try {
            Files.writeString(FabricLoader.getInstance().getGameDir().resolve(name), content);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String sourcePrompt(BlockedAttackSource source) {
        if (source == BlockedAttackSource.SHIELD) {
            return "盾挡 → 弹";
        }
        if (source == BlockedAttackSource.GUARD) {
            return "前臂格挡 → 弹";
        }
        return "硬物挡 → 弹";
    }

    private static void sendP2Prompt(TestSingleplayerContext world, UUID playerId, String prompt) {
        world.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            player.sendSystemMessage(Component.literal(prompt), true /* overlay */);
        });
    }

    private static void showP2Prompt(ClientGameTestContext context, String prompt) {
        context.runOnClient(client -> client.gui.hud.setOverlayMessage(
                Component.literal(prompt), false /* animateColor */));
    }

    private static void waitForCooldown(ClientGameTestContext context) {
        context.waitTicks(P2_SPRING_BACK_COOLDOWN_TICKS + SETTLE_TICKS);
    }

    private static long currentMillis(ServerPlayer player) {
        return TechniqueFabric.service().statusHost(player.getUUID()).orElseThrow()
                .inspect().body().settledAt().elapsedMillis();
    }

    private static boolean hasBlockedSource(ServerPlayer player, BlockedAttackSource source) {
        var body = TechniqueFabric.service().statusHost(player.getUUID()).orElseThrow().inspect().body();
        var progress = TechniqueState.read(body);
        return progress.hasBlockedAttack() && progress.getBlockedAttack().getSource() == source;
    }

    private record P2Arena(UUID defenderId, UUID targetId, int cameraEntityId, BlockPos wall) { }

    private record P2Segment(String name, BlockedAttackSource source) { }

    private record P2FrameCapture(ClientGameTestContext context, TestSingleplayerContext world,
                                  P2Arena arena, String name, List<String> rows) { }

    private static UUID prepare(ClientGameTestContext context, TestSingleplayerContext world) {
        // 建立可重复的创造模式场景，学习上卷并把卷轴、功法谱和两把剑放进快捷栏。
        world.getConnection().waitForChunksRender();
        world.getServer().runCommand("time set noon");
        world.getServer().runCommand("weather clear");
        world.getServer().runCommand("gamerule advance_time false");
        world.getServer().runCommand("gamemode creative @a");
        world.getServer().runCommand("meridian opening complete @a all");
        world.getServer().runCommand("meridian body realm @a wandering_qi");
        world.getServer().runCommand("meridian qi fill @a");
        world.getServer().runCommand("meridian technique learn @a " + PREFIX + "upper");
        UUID playerId = world.getServer().computeOnServer(
                server -> server.getPlayerList().getPlayers().getFirst().getUUID());
        world.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            player.getInventory().clearContent();
            player.getInventory().setItem(0, stack(UPPER_SCROLL));
            player.getInventory().setItem(1, stack(CODEX));
            player.getInventory().setItem(2, new ItemStack(Items.GOLDEN_SWORD));
            player.getInventory().setItem(3, new ItemStack(Items.IRON_SWORD));
            player.getInventory().setSelectedSlot(0);
            player.setGameMode(GameType.CREATIVE);
            player.snapTo(0.5, player.getY(), 0.5, 0, 0);
        });
        context.waitTicks(SETTLE_TICKS * 5);
        context.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_BACK));
        context.waitTicks(SETTLE_TICKS);
        return playerId;
    }

    private static void captureScroll(ClientGameTestContext context, TestSingleplayerContext world) {
        // 先拍物品图标，再真实右键阅读上卷卷轴。
        context.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
        clearClientPopups(context);
        context.waitTicks(SETTLE_TICKS);
        context.takeScreenshot(TestScreenshotOptions.of("upper_scroll_item").disableCounterPrefix());
        context.runOnClient(client -> client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND));
        context.waitTicks(SETTLE_TICKS);
        context.takeScreenshot(TestScreenshotOptions.of("upper_scroll_page").disableCounterPrefix());
        context.runOnClient(client -> client.gui.setScreen(null));
        context.waitTicks(SETTLE_TICKS);
    }

    private static void captureCodex(ClientGameTestContext context, TestSingleplayerContext world) {
        // 打开功法谱，功法目录会显示流金剑、上卷以及绕和抖的图标与文案。
        selectHotbar(context, 1);
        context.runOnClient(client -> client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND));
        context.waitTicks(SETTLE_TICKS);
        context.runOnClient(client -> {
            if (client.gui.screen() == null
                    || !client.gui.screen().getClass().getSimpleName().contains("CodexScreen")) {
                throw new IllegalStateException("右键功法谱没有打开功法谱页面：" + client.gui.screen());
            }
            Object screen = client.gui.screen();
            selectCodexBook(screen, MOLTEN_SWORD);
            scrollCodexBookIntoView(screen, MOLTEN_SWORD);
            scrollCodexPageIntoView(screen);
        });
        clearClientPopups(context);
        context.waitTicks(SETTLE_TICKS);
        context.takeScreenshot(TestScreenshotOptions.of("codex_molten_sword").disableCounterPrefix());
        context.runOnClient(client -> client.gui.setScreen(null));
        context.waitTicks(SETTLE_TICKS);
    }

    private static void clearClientPopups(ClientGameTestContext context) {
        // 截图只保留游戏画面，避免测试环境的提示、聊天和新手引导遮住内容。
        context.runOnClient(client -> {
            client.getTutorial().setStep(TutorialSteps.NONE);
            client.gui.hud.getChat().clearMessages(false /* clearSentHistory */);
            client.gui.toastManager().clear();
        });
    }

    private static void selectCodexBook(Object screen, String bookId) {
        // 功法谱没有公开选择器，客户端测试通过屏幕反射选择目标书籍。
        try {
            Object panel = codexPanel(screen);
            Method select = panel.getClass().getDeclaredMethod("select", String.class);
            select.setAccessible(true);
            select.invoke(panel, bookId);
            Method selectedBook = panel.getClass().getDeclaredMethod("selectedBook");
            selectedBook.setAccessible(true);
            String selected = (String) selectedBook.invoke(panel);
            if (!bookId.equals(selected)) {
                throw new AssertionError("功法谱没有选中《流金剑》：" + selected);
            }
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("无法选中《流金剑》", failure);
        }
    }

    private static void scrollCodexBookIntoView(Object screen, String bookId) {
        // 左页目录按书籍行排列，截图前把《流金剑》行滚入可见区域。
        try {
            Object panel = codexPanel(screen);
            var contentField = panel.getClass().getDeclaredField("content");
            contentField.setAccessible(true);
            ParentUIComponent content = (ParentUIComponent) contentField.get(panel);
            ScrollContainer<?> indexScroll = content.childById(ScrollContainer.class, "index-scroll");
            ParentUIComponent index = (ParentUIComponent) indexScroll.child();
            var catalogField = panel.getClass().getDeclaredField("catalog");
            catalogField.setAccessible(true);
            Object catalog = catalogField.get(panel);
            Method books = catalog.getClass().getDeclaredMethod("books");
            books.setAccessible(true);
            var bookList = (java.util.List<?>) books.invoke(catalog);
            int bookIndex = IntStream.range(0, bookList.size())
                    .filter(indexPosition -> bookId.equals(bookId(bookList.get(indexPosition))))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("功法谱没有《流金剑》书目"));
            UIComponent row = index.children().get(bookIndex);
            indexScroll.scrollTo(row);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("无法将《流金剑》滚入功法谱目录", failure);
        }
    }

    private static String bookId(Object book) {
        try {
            Method id = book.getClass().getDeclaredMethod("id");
            id.setAccessible(true);
            return (String) id.invoke(book);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("无法读取功法谱书目", failure);
        }
    }

    private static void scrollCodexPageIntoView(Object screen) {
        // 右页滚到上卷卷头，保证卷名、图标和两式名称同时进入截图。
        try {
            Object panel = codexPanel(screen);
            var contentField = panel.getClass().getDeclaredField("content");
            contentField.setAccessible(true);
            ParentUIComponent content = (ParentUIComponent) contentField.get(panel);
            ScrollContainer<?> pageScroll = content.childById(ScrollContainer.class, "page-scroll");
            ParentUIComponent page = (ParentUIComponent) pageScroll.child();
            if (page.children().isEmpty()) {
                throw new AssertionError("功法谱右页没有上卷内容");
            }
            pageScroll.scrollTo(page.children().get(0));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("无法将上卷滚入功法谱右页", failure);
        }
    }

    private static Object codexPanel(Object screen) throws ReflectiveOperationException {
        Method panelAccessor = screen.getClass().getDeclaredMethod("panel");
        panelAccessor.setAccessible(true);
        return panelAccessor.invoke(screen);
    }

    private static Vindicator spawnTarget(TestSingleplayerContext world, UUID playerId) {
        // 生成固定位置的无 AI 目标，保证连拍中能看见两式的落点反馈。
        return world.getServer().computeOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            Vindicator target = EntityTypes.VINDICATOR.create(server.overworld(), EntitySpawnReason.COMMAND);
            if (target == null) {
                throw new IllegalStateException("无法生成录像目标");
            }
            target.snapTo(player.getX(), player.getY(), player.getZ() + 3, 180, 0);
            target.setNoAi(true /* aiDisabled */);
            target.setSilent(true /* silent */);
            target.setPersistenceRequired();
            server.overworld().addFreshEntity(target);
            return target;
        });
    }

    private static void captureWind(ClientGameTestContext context, TestSingleplayerContext world,
                                    UUID playerId, Vindicator target) {
        // 金剑施放绕，检查目标确实受到了命中伤害后保留连拍证据。
        selectHotbar(context, 2);
        setWeapon(world, playerId, Items.GOLDEN_SWORD);
        float before = health(world, target);
        cast(world, playerId, "wind_around");
        captureFrames(context, "wind_gold");
        assertDamaged(world, target, before, "金剑绕没有命中目标");
        context.waitTicks(COOLDOWN_TICKS);
    }

    private static void captureQuiver(ClientGameTestContext context, TestSingleplayerContext world,
                                      UUID playerId, Vindicator target) {
        // 铁剑施放抖，确认三次连点至少产生了可见的实际伤害反馈。
        selectHotbar(context, 3);
        setWeapon(world, playerId, Items.IRON_SWORD);
        float before = health(world, target);
        cast(world, playerId, "quiver");
        captureFrames(context, "quiver_iron");
        assertDamaged(world, target, before, "铁剑抖没有命中目标");
    }

    private static void captureFrames(ClientGameTestContext context, String name) {
        // 按固定间隔保存起手、命中和收势帧，供录像拼接和逐张检查。
        for (int frame = 0; frame < CAST_FRAME_COUNT; frame++) {
            context.waitTicks(FRAME_INTERVAL_TICKS);
            String screenshot = String.format(Locale.ROOT, "%s_%02d", name, frame);
            context.takeScreenshot(TestScreenshotOptions.of(screenshot).disableCounterPrefix());
        }
    }

    private static void selectHotbar(ClientGameTestContext context, int slot) {
        // 切换客户端快捷栏并等待服务端同步手上物品。
        context.runOnClient(client -> client.player.getInventory().setSelectedSlot(slot));
        context.waitTicks(SETTLE_TICKS);
    }

    private static void setWeapon(TestSingleplayerContext world, UUID playerId,
                                  net.minecraft.world.item.Item item) {
        // 服务端直接替换主手，保持录像测试不受快捷栏旧状态影响。
        world.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item));
        });
    }

    private static void cast(TestSingleplayerContext world, UUID playerId, String technique) {
        // 通过正式功法服务提交请求，客户端只负责触发和记录证据。
        TechniqueReference reference = TechniqueReference.newBuilder()
                .setId(PREFIX + technique)
                .setVersion(1)
                .build();
        HorizontalDirection direction = HorizontalDirection.newBuilder()
                .setX(0)
                .setZ(1)
                .build();
        CastRequest request = CastRequest.newBuilder()
                .setBodyId(playerId.toString())
                .setTechnique(reference)
                .setCastId(UUID.randomUUID().toString())
                .setDirection(direction)
                .build();
        CastOutcome outcome = world.getServer().computeOnServer(
                server -> TechniqueFabric.service().cast(playerId, request));
        if (outcome.getFailure() != TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED) {
            throw new IllegalStateException("客户端施放失败：" + outcome);
        }
    }

    private static ItemStack stack(String itemId) {
        // 从注册表取得正式物品，避免客户端测试复制物品定义。
        var item = BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId));
        if (item == null) {
            throw new IllegalStateException("缺少客户端物品：" + itemId);
        }
        return new ItemStack(item);
    }

    private static float health(TestSingleplayerContext world, Vindicator target) {
        // 在逻辑服务器读取目标生命，避免用客户端渲染状态代替结算结果。
        return world.getServer().computeOnServer(server -> target.getHealth());
    }

    private static void assertDamaged(TestSingleplayerContext world, Vindicator target,
                                      float before, String message) {
        // 两式的录像必须对应真实命中，而不是只有施放成功回执。
        float after = health(world, target);
        if (!(after < before)) {
            throw new IllegalStateException(message + "：施放前=" + before + "，施放后=" + after);
        }
    }
}
