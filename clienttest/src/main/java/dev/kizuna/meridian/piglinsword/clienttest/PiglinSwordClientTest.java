package dev.kizuna.meridian.piglinsword.clienttest;

import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastOutcome;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HorizontalDirection;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import io.wispforest.owo.ui.container.ScrollContainer;
import io.wispforest.owo.ui.core.ParentUIComponent;
import io.wispforest.owo.ui.core.UIComponent;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.IntStream;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.CameraType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.illager.Vindicator;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

/** P0 客户端证据，截图功法谱、卷轴和两种持剑施放反馈。 */
public final class PiglinSwordClientTest implements FabricClientGameTest {
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

    @Override
    public void runTest(ClientGameTestContext context) {
        context.getInput().resizeWindow(WINDOW_WIDTH, WINDOW_HEIGHT);
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            UUID playerId = prepare(context, world);
            captureScroll(context, world);
            captureCodex(context, world);
            Vindicator target = spawnTarget(world, playerId);
            captureWind(context, world, playerId, target);
            captureQuiver(context, world, playerId, target);
        }
    }

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
