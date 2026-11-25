package dev.kizuna.meridian.piglinsword;

import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.world.TechniqueStatusBenefits;
import dev.kizuna.meridian.technique.fabric.world.TechniqueWorldEffects;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.server.TechniqueHost;
import dev.kizuna.meridian.technique.server.TechniqueScheduler;
import dev.kizuna.meridian.technique.server.TechniqueState;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import dev.kizuna.meridian.vanilla.fabric.VanillaStrikeContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

public final class PiglinSwordSplashGameTest {
    private static final String PREFIX = "meridian_piglin_sword:";
    private static final String SPLASH = PREFIX + "gold_splash";
    private static final String FLOW = PREFIX + "molten_flow";
    private static final String HOT = PREFIX + "molten";
    private static final String COATING = PREFIX + "gold_caked";
    private static final int TIMEOUT = 360;
    private static final int FLOW_READY = 28;
    private static final int RELEASE_READY = 16;
    private static final int COATING_TICKS = 160;
    private static final int DROP_LIMIT = 9;
    private static final int STACK = 3;
    private static final int MAX_PARTS = 3;
    private static final int JOIN_PROTECTION = 62;
    private static final double SIDE = 0.8;
    private static final double FORWARD = 3;
    private static final double OUTSIDE_ANGLE = 2.2;
    private static final double OUTSIDE_RANGE = 4.5;
    private static final double SEARCH = 5;
    private static final int CROWD = 5;
    private static final int PER_TARGET = 2;

    @GameTest
    public void coldSwordRejectsBeforeWarmup(GameTestHelper test) {
        // 下卷学会泼金仍须现场有热剑，拒绝不扣剑。
        var actor = PiglinSwordGameTest.lower(test).player;
        var result = PiglinSwordGameTest.cast(actor, SPLASH, "cold");
        test.assertTrue(result.getReason().equals("剑身还没烧软"), "冷剑没有在起手前拒绝：" + result);
        test.assertTrue(actor.getMainHandItem().is(Items.GOLDEN_SWORD), "冷剑被消耗");
        test.succeed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void offhandMissConsumesWholeStackOnce(GameTestHelper test) {
        // 两手都有金剑时，选手和重试仍只认最初烧热的那一叠。
        var actor = PiglinSwordGameTest.lower(test).player;
        actor.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        var stack = new ItemStack(Items.GOLDEN_SWORD, STACK);
        stack.set(DataComponents.UNBREAKABLE, net.minecraft.util.Unit.INSTANCE);
        stack.set(DataComponents.MAX_STACK_SIZE, STACK);
        actor.setItemInHand(InteractionHand.OFF_HAND, stack);
        var replay = new AtomicReference<EffectIntent>();
        var spare = new ItemStack(Items.GOLDEN_SWORD);
        var replacement = new ItemStack(Items.GOLDEN_SWORD);
        heat(test, actor).thenExecute(() -> {
            actor.setItemInHand(InteractionHand.MAIN_HAND, spare);
            cast(test, actor, SPLASH, "offhand");
            replay.set(releaseIntent(actor));
        }).thenExecuteAfter(RELEASE_READY, () -> {
            test.assertTrue(actor.getOffhandItem().isEmpty(), "挥空没有整叠消耗副手剑");
            test.assertTrue(actor.getMainHandItem() == spare && !has(actor, HOT), "选错剑或流金仍在");
            test.assertTrue(!has(actor, PREFIX + "quenched"), "正常释放误触淬硬");
            actor.setItemInHand(InteractionHand.OFF_HAND, replacement);
            actor.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            TechniqueStatusBenefits.apply(actor, TechniqueStatusBenefits.get(HOT).benefit());
            var retry = new TechniqueHost.EffectSubmission("retry-release", replay.get());
            TechniqueFabric.service().statusHost(actor.getUUID()).orElseThrow().submitEffect(retry);
        }).thenExecuteAfter(RELEASE_READY, () ->
                test.assertTrue(actor.getOffhandItem() == replacement, "重试消耗了新剑")).thenSucceed();
    }

    private static EffectIntent releaseIntent(ServerPlayer actor) {
        // 从起手快照按真实时间线生成同一效果身份，重发时只换请求号。
        var host = TechniqueFabric.service().statusHost(actor.getUUID()).orElseThrow();
        var active = TechniqueState.read(host.inspect().body()).getActiveCast();
        var ref = active.getRequest().getTechnique();
        var definition = TechniqueFabric.service().registry().find(ref).orElseThrow();
        return TechniqueScheduler.effectsAt(definition, active, active.getWarmupEndMillis()).stream()
                .filter(EffectIntent::hasWeaponStrike).findFirst().orElseThrow();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void swapDuringWarmupSparesBothSwords(GameTestHelper test) {
        // 起手后移动热剑并换入同款，出手时两把剑都须保留。
        var actor = PiglinSwordGameTest.lower(test).player;
        var original = actor.getMainHandItem();
        var other = original.copy();
        var target = cow(test, actor, 0, FORWARD);
        heat(test, actor).thenExecute(() -> {
            cast(test, actor, SPLASH, "swap");
            actor.setItemInHand(InteractionHand.OFF_HAND, original);
            actor.setItemInHand(InteractionHand.MAIN_HAND, other);
        }).thenExecuteAfter(RELEASE_READY, () -> {
            test.assertTrue(actor.getOffhandItem() == original && actor.getMainHandItem() == other, "换手后仍扣剑");
            test.assertTrue(!has(target, COATING) && has(actor, PREFIX + "quenched"), "换手后仍然泼出");
        }).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void mobWorldHostConsumesTheHeatedSword(GameTestHelper test) {
        // 用目录调度的同一效果驱动生物世界宿主，直接观察所持整剑与绑定状态。
        var actor = PiglinSwordGameTest.lower(test).player;
        var mob = cow(test, actor, OUTSIDE_ANGLE, 0);
        var target = cow(test, mob, 0, FORWARD);
        mob.setYRot(0);
        mob.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GOLDEN_SWORD));
        TechniqueStatusBenefits.apply(mob, TechniqueStatusBenefits.get(HOT).benefit());
        var world = new TechniqueWorldEffects(mob, () -> TechniqueWorldEffects.Policy.MOB, target::equals,
                (attacker, source, strike) -> {
                    try (var ignored = VanillaStrikeContext.attack(attacker, source)) {
                        strike.run();
                    }
                }, "meridian_piglin_sword");
        heat(test, actor).thenExecute(() -> {
            cast(test, actor, SPLASH, "mob-intent");
            world.strike(releaseIntent(actor));
            test.assertTrue(mob.getMainHandItem().isEmpty() && !has(mob, HOT), "生物出手未耗剑或未灭热");
            test.assertTrue(has(target, COATING), "生物扇面没有挂金");
        }).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void creativeKeepsSwordAndReleasesHeat(GameTestHelper test) {
        // 创造只豁免扣物，照常结束流金并影响目标。
        var actor = PiglinSwordGameTest.lower(test).player;
        actor.setGameMode(GameType.CREATIVE);
        var sword = actor.getMainHandItem();
        var target = cow(test, actor, 0, FORWARD);
        heat(test, actor).thenExecute(() -> cast(test, actor, SPLASH, "creative"))
                .thenExecuteAfter(RELEASE_READY, () -> {
                    test.assertTrue(actor.getMainHandItem() == sword && !has(actor, HOT), "创造扣剑或未结束流金");
                    test.assertTrue(has(target, COATING), "创造泼金没有命中");
                }).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void sectorBurnsAndSlowsOnlyHitParts(GameTestHelper test) {
        // 两个真实身体分别核对命中皮肤和迟滞组织，另设角外与距离外的目标。
        var actor = PiglinSwordGameTest.lower(test).player;
        var left = PiglinSwordGameTest.lower(test).player;
        var right = PiglinSwordGameTest.lower(test).player;
        left.setPos(actor.position().add(-SIDE, 0, FORWARD));
        right.setPos(actor.position().add(SIDE, 0, FORWARD));
        var first = PiglinSwordGameTest.watchDamage(left);
        var second = PiglinSwordGameTest.watchDamage(right);
        var outside = cow(test, actor, OUTSIDE_ANGLE, FORWARD);
        var far = cow(test, actor, 0, OUTSIDE_RANGE);
        test.startSequence().thenExecuteAfter(JOIN_PROTECTION, () -> cast(test, actor, FLOW, "heat"))
                .thenExecuteAfter(FLOW_READY, () -> cast(test, actor, SPLASH, "sector"))
                .thenExecuteAfter(RELEASE_READY, () -> {
                    checkHits(test, left, first);
                    checkHits(test, right, second);
                    test.assertTrue(!has(outside, COATING) && !has(far, COATING), "命中扇角或距离以外的目标");
                }).thenSucceed();
    }

    private static void checkHits(GameTestHelper test, ServerPlayer player, List<VanillaDamageFabric.DamageEvent> log) {
        // 同一部位只算一次；修正的组织集合应与这些部位上的出力组织完全相等。
        var parts = new HashSet<String>();
        var hits = log.stream().filter(event -> event.source().is(DamageTypeTags.IS_FIRE)).toList();
        test.assertTrue(has(player, COATING) && !hits.isEmpty() && hits.size() <= MAX_PARTS, "缺挂金或热伤");
        for (var hit : hits) {
            String tissueId = hit.input().hit().orElseThrow().getTissueId();
            var tissue = hit.result().body().getStructure().getTissuesList().stream()
                    .filter(t -> t.getId().equals(tissueId)).findFirst().orElseThrow();
            test.assertTrue(parts.add(tissue.getPartId()), "同一部位重复结算");
        }
        var body = VanillaDamageFabric.inspectBody(player).orElseThrow();
        var expected = body.getStructure().getTissuesList().stream().filter(t -> parts.contains(t.getPartId())
                && t.getCapacity().getBurstWatts() > 0).map(t -> t.getId()).sorted().toList();
        var actual = body.getModifiersList().stream().filter(m -> m.getId().startsWith(COATING + "/")
                && m.getTempoPeak() < 1).map(m -> m.getTissueId()).sorted().toList();
        test.assertTrue(!expected.isEmpty() && actual.equals(expected), "迟滞部位不符：" + actual + " / " + expected);
    }

    @GameTest(maxTicks = TIMEOUT)
    public void coatingDropsTwoAtFeetAfterEightSeconds(GameTestHelper test) {
        // 命中后分开目标并让其中一头入水，到期检查两个独立落点。
        var actor = PiglinSwordGameTest.lower(test).player;
        var left = cow(test, actor, -SIDE, FORWARD);
        var right = cow(test, actor, SIDE, FORWARD);
        var sequence = heat(test, actor).thenExecute(() -> cast(test, actor, SPLASH, "two"));
        sequence.thenWaitUntil(() -> test.assertTrue(has(left, COATING) && has(right, COATING), "两目标未挂金"))
                .thenExecute(() -> {
                    var status = left.getEffect(TechniqueStatusBenefits.get(COATING).effect());
                    test.assertTrue(status.getDuration() >= COATING_TICKS - 1, "挂金初始时长错误");
                    left.setPos(actor.position().add(-FORWARD, 0, FORWARD));
                    right.setPos(actor.position().add(FORWARD, 0, FORWARD));
                    test.getLevel().setBlockAndUpdate(left.blockPosition(), Blocks.WATER.defaultBlockState());
                }).thenExecuteAfter(COATING_TICKS - RELEASE_READY, () -> {
                    test.assertTrue(has(left, COATING) && has(right, COATING), "挂金提早结束");
                    test.assertTrue(nuggets(actor, SEARCH) == 0, "八秒前已经掉金");
                }).thenWaitUntil(() -> {
                    int first = nuggets(left, 1);
                    int second = nuggets(right, 1);
                    test.assertTrue(first == PER_TARGET && second == PER_TARGET, "目标脚下金粒：" + first + "/" + second);
                }).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void crowdedSectorSharesOneRefundLimit(GameTestHelper test) {
        // 第五个目标仍然命中，最后一份回落只剩一粒。
        var actor = PiglinSwordGameTest.lower(test).player;
        var targets = new ArrayList<Cow>();
        for (int index = 0; index < CROWD; index++) {
            targets.add(cow(test, actor, 0, FORWARD));
        }
        heat(test, actor).thenExecute(() -> cast(test, actor, SPLASH, "crowd"))
                .thenWaitUntil(() -> test.assertTrue(targets.stream().allMatch(t -> has(t, COATING)), "未全部挂金"))
                .thenWaitUntil(() -> test.assertTrue(nuggets(actor, SEARCH) > 0, "没有回落"))
                .thenExecuteAfter(1, () -> {
                    int total = nuggets(actor, SEARCH);
                    test.assertTrue(total == DROP_LIMIT, "每把剑应回九粒，实际 " + total);
                }).thenSucceed();
    }

    private static GameTestSequence heat(GameTestHelper test, ServerPlayer actor) {
        // 从正式目录上热，等待收势。
        cast(test, actor, FLOW, "heat");
        return test.startSequence().thenIdle(FLOW_READY);
    }

    private static void cast(GameTestHelper test, ServerPlayer actor, String technique, String id) {
        // 保留正式宿主给出的拒绝原因。
        var result = PiglinSwordGameTest.cast(actor, technique, id);
        test.assertTrue(result.getFailure() == TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED, "起手失败：" + result);
    }

    private static boolean has(LivingEntity target, String id) {
        // 使用同步至客户端的原版状态实例。
        return target.hasEffect(TechniqueStatusBenefits.get(id).effect());
    }

    private static Cow cow(GameTestHelper test, LivingEntity actor, double x, double z) {
        // 固定扇区内外的位置，防止走动和实体间推挤。
        var level = test.getLevel();
        var cow = EntityTypes.COW.create(level, EntitySpawnReason.COMMAND);
        cow.setNoAi(true /* noAi */);
        cow.setNoGravity(true /* noGravity */);
        cow.noPhysics = true;
        cow.setPos(actor.position().add(x, 0, z));
        test.assertTrue(level.addFreshEntity(cow), "测试目标生成失败");
        return cow;
    }

    private static int nuggets(LivingEntity center, double radius) {
        // 允许原版堆叠，按物品数量统计。
        var area = center.getBoundingBox().inflate(radius);
        return center.level().getEntitiesOfClass(ItemEntity.class, area, e -> e.getItem().is(Items.GOLD_NUGGET))
                .stream().mapToInt(item -> item.getItem().getCount()).sum();
    }
}
