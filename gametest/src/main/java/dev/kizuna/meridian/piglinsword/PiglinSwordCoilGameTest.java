package dev.kizuna.meridian.piglinsword;

import dev.kizuna.meridian.protocol.pb.BodyMessages.CapabilityModifier;
import dev.kizuna.meridian.protocol.pb.BodyMessages.CurvePoint;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageKind;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.world.TechniqueStatusBenefits;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastEntry;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastOutcome;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Hand;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueProgress;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.server.TechniqueHost;
import dev.kizuna.meridian.technique.server.TechniqueService;
import dev.kizuna.meridian.technique.server.TechniqueState;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import static dev.kizuna.meridian.piglinsword.PiglinSwordGameTest.*;

public final class PiglinSwordCoilGameTest {
    private static final String PREFIX = "meridian_piglin_sword:";
    private static final String COIL = PREFIX + "coil";
    private static final String WIND = PREFIX + "wind_around";
    private static final String COILED = PREFIX + "coiled";
    private static final String COILING = PREFIX + "coiling";
    private static final int LOCK_TICKS = 50;
    private static final int WINDOW_TICKS = 10;
    private static final int MILLIS_PER_TICK = 50;
    private static final int HALF_WARMUP = 175;
    private static final int LOGIN_GRACE = 61;
    private static final int TIMEOUT = 180;
    private static final double INITIAL_DISTANCE = 2;
    private static final double MAX_DISTANCE = 3;
    private static final double OUTSIDE = 5;
    private static final float FACING_CASTER = 180;
    private static final double COUNTER_FORCE = 1.5;
    private static final long FORCE_DURATION = 15000;
    private static final long STAGGER_MILLIS = 400;
    private static final double STAGGER_MOVEMENT = 0.5;
    private static final double GOLDEN_SWORD_DAMAGE = 4;
    private static final double FIRST_CUT = 0.3;
    private static final double HOLD_CUT = 0.1;
    private static final double PULL_CUT = 1.5;
    private static final double DOSE_EPSILON = 0.0001;
    private static final List<Double> CUT_RATIOS = List.of(FIRST_CUT, HOLD_CUT, HOLD_CUT, HOLD_CUT, HOLD_CUT, PULL_CUT);

    private record Duel(GameTestHelper test, Fixture attacker, Fixture defender,
                        List<VanillaDamageFabric.DamageEvent> hits) {
        ServerPlayer caster() {
            // 施放侧和被缠侧始终取两具独立的登录身体。
            return attacker.player;
        }

        ServerPlayer target() {
            // 靶手也走玩家的正式普攻、施放和效果清除入口。
            return defender.player;
        }

        GameTestSequence start() {
            // 等登录保护结束，伤害和身体组织都能参与正常结算。
            return test.startSequence().thenIdle(LOGIN_GRACE).thenExecute(() -> {
                var outcome = cast(caster(), COIL, "coil");
                test.assertTrue(accepted(outcome), "缠未起手：" + outcome);
            });
        }

        void done() {
            // 假连接在服务器仍能结算身体时退出，死亡玩家也走正常下线路径。
            attacker.close();
            defender.close();
        }

        GameTestSequence bind() {
            // 两个效果应在同一服务端事件中可见，并分别同步完整倒计时。
            return start().thenWaitUntil(() -> {
                test.assertTrue(has(caster(), COILING) && has(target(), COILED), "双方未建立牵制");
                assertStatusPacket(test, attacker, COILING, LOCK_TICKS);
                assertStatusPacket(test, defender, COILED, LOCK_TICKS);
            });
        }

        void cleared() {
            // 双方效果在同一次清理后都应消失。
            test.assertFalse(has(target(), COILED), "目标仍带缠住");
            test.assertFalse(has(caster(), COILING), "施放者仍带持缠");
        }

        void pulled(String part) {
            // 看身体收到的伤害及固定部位，避免只验证挥手或显示状态。
            var pulls = pulls();
            test.assertTrue(pulls.size() == 1, "预期一次抽回伤害，实际 " + pulls.size());
            String actual = pulls.getFirst().input().hit().orElseThrow().getPartId();
            test.assertTrue(actual.equals(part), "抽回打在 " + actual + "，预期 " + part);
            cleared();
        }

        List<VanillaDamageFabric.DamageEvent> pulls() {
            // 抽回是唯一超过普通金剑基础剂量的一击，小割次数和剂量另逐笔检查。
            return cuts().stream().filter(hit -> hit.input().rawDamage() > GOLDEN_SWORD_DAMAGE).toList();
        }

        List<VanillaDamageFabric.DamageEvent> cuts() {
            // 移动可能造成环境伤害，只取本次兵击。
            return hits.stream().filter(event -> event.source().typeHolder().unwrapKey().orElseThrow()
                    .identifier().toString().equals(COIL + "_main_hand")).toList();
        }

        void assertCut(int count, String part) {
            // 每个时点检查命中数、原前臂、斩伤和真实护甲前剂量。
            var strikes = cuts();
            test.assertTrue(strikes.size() == count, "应有 " + count + " 次割击，实际 " + strikes.size());
            var input = strikes.getLast().input();
            double expected = GOLDEN_SWORD_DAMAGE * CUT_RATIOS.get(count - 1);
            test.assertTrue(input.kind() == DamageKind.CUTTING && input.hit().orElseThrow().getPartId().equals(part),
                    "割击种类或前臂错误：" + input);
            test.assertTrue(Math.abs(input.rawDamage() - expected) < DOSE_EPSILON,
                    "第 " + count + " 击实际剂量 " + input.rawDamage() + "，预期 " + expected);
        }

        long sincePull() {
            // 用与起手相同的身体时钟对齐边界，实体和 GameTest 回调可能相差一刻。
            var host = TechniqueFabric.service().statusHost(caster().getUUID()).orElseThrow();
            var body = host.inspect().body();
            return body.settledAt().elapsedMillis() - TechniqueState.read(body).getBindReleaseMillis();
        }
    }

    private static Duel duel(GameTestHelper test) {
        // 玩家按各自相对场地摆位，目标面对施放者，免受场地分配顺序影响。
        var attacker = lower(test);
        var defender = lower(test);
        var pos = attacker.player.position().add(0, 0, INITIAL_DISTANCE);
        defender.player.snapTo(pos.x, pos.y, pos.z, FACING_CASTER, 0);
        return new Duel(test, attacker, defender, watchDamage(defender.player));
    }

    private static boolean has(ServerPlayer player, String id) {
        // 读取持有者的原版效果集合。
        return player.hasEffect(TechniqueStatusBenefits.get(id).effect());
    }

    private static boolean accepted(CastOutcome outcome) {
        // 费用与选手均由服务端起手回执确认。
        return outcome.getFailure() == TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED;
    }

    private static TechniqueProgress progress(ServerPlayer player) {
        // 校验真实身体记录的选手、剪辑时刻与抽回事实。
        var host = TechniqueFabric.service().statusHost(player.getUUID()).orElseThrow();
        return TechniqueState.read(host.inspect().body());
    }

    private static CastOutcome follow(ServerPlayer caster) {
        // 显式请求承接，窗外无法被普通起手掩盖。
        var reference = TechniqueReference.newBuilder()
                .setId(WIND)
                .setVersion(1)
                .build();
        var request = CastRequest.newBuilder()
                .setBodyId(caster.getStringUUID())
                .setCastId(UUID.randomUUID().toString())
                .setTechnique(reference)
                .setEntry(CastEntry.FOLLOW_UP)
                .build();
        return TechniqueFabric.service().cast(caster.getUUID(), request);
    }

    @GameTest(maxTicks = TIMEOUT)
    public void sixTimedCutsShareTheOriginalForearm(GameTestHelper test) {
        // 第一次小割与建缠同刻；每隔十刻割原臂，末击才解开。
        var duel = duel(test);
        var sequence = duel.bind().thenExecute(() -> {
            duel.assertCut(1, "right_forearm");
            duel.caster().setYRot(FACING_CASTER);
        });
        for (int count = 2; count <= CUT_RATIOS.size(); count++) {
            int expected = count;
            sequence.thenIdle(WINDOW_TICKS - 1).thenExecute(() -> duel.assertCut(expected - 1, "right_forearm"))
                    .thenIdle(1).thenExecute(() -> duel.assertCut(expected, "right_forearm"));
        }
        sequence.thenExecute(duel::cleared).thenExecute(duel::done).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void releaseDuringHoldStopsFurtherCuts(GameTestHelper test) {
        // 第二下刚落就驱散，原目标留在正面三格内，后续调度仍不能补伤。
        var duel = duel(test);
        duel.bind().thenWaitUntil(() -> duel.assertCut(2, "right_forearm")).thenExecute(() -> {
            duel.target().removeEffect(TechniqueStatusBenefits.get(COILED).effect());
            duel.cleared();
        }).thenIdle(LOCK_TICKS).thenExecute(() -> duel.assertCut(2, "right_forearm"))
                .thenExecute(duel::done).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void mainHandCannotAttackOrCastAndOffHandCanCast(GameTestHelper test) {
        // 普攻与需兵器的式都封目标主手；给副手配兵后应能实际起手。
        var duel = duel(test);
        duel.bind().thenWaitUntil(() -> {
            long stun = TechniqueFabric.service().staggerRemainingMillis(duel.target().getUUID());
            test.assertTrue(stun == 0 && has(duel.target(), COILED), "等首次割击的受击僵直结束");
        }).thenExecute(() -> {
            var target = duel.target();
            var caster = duel.caster();
            target.attack(caster);
            test.assertTrue(caster.hurtTime == 0, "缠住的主手发动了普通攻击");
            target.swinging = false;
            target.swing(InteractionHand.MAIN_HAND);
            test.assertFalse(target.swinging, "缠住的主手仍可挥剑");
            test.assertFalse(accepted(cast(target, WIND, "locked")), "需主手的式仍能起手");
            test.assertFalse(accepted(cast(caster, WIND, "holder")), "持缠的手能出其他式");
            target.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.CROSSBOW));
            target.startUsingItem(InteractionHand.MAIN_HAND);
            test.assertFalse(target.isUsingItem(), "缠住的手还能重新给弩上弦");
            var use = UseItemCallback.EVENT.invoker().interact(target, target.level(), InteractionHand.MAIN_HAND);
            test.assertTrue(use == InteractionResult.FAIL, "锁臂仍能右键击发弩");
            target.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GOLDEN_SWORD));
            target.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.GOLDEN_SWORD));
            var free = cast(target, WIND, "free");
            test.assertTrue(accepted(free), "未缠的副手不能起手：" + free.getFailure());
            test.assertTrue(progress(target).getActiveCast().getSelectedHand() == Hand.OFF_HAND, "选手落回锁臂");
        }).thenExecute(duel::done).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void offHandPullStaysOnOriginalForearmAndEnablesWind(GameTestHelper test) {
        // 建缠后转身，武器换到另一只手，抽回仍落在原先的副手前臂。
        var duel = duel(test);
        duel.target().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        duel.target().setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.GOLDEN_SWORD));
        duel.bind().thenExecute(() -> {
            duel.caster().setYRot(FACING_CASTER);
            duel.target().setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            duel.target().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GOLDEN_SWORD));
        }).thenWaitUntil(() -> {
            var effect = duel.caster().getEffect(TechniqueStatusBenefits.get(COILING).effect());
            test.assertTrue(effect != null && effect.getDuration() == 1 && duel.pulls().isEmpty(), "末刻之前已抽回");
        }).thenWaitUntil(() -> duel.pulled("left_forearm")).thenWaitUntil(() ->
                test.assertTrue(duel.sincePull() == WINDOW_TICKS * MILLIS_PER_TICK, "未到承接末刻")).thenExecute(() -> {
            var outcome = follow(duel.caster());
            test.assertTrue(accepted(outcome), "边界内不能接绕：" + outcome);
            test.assertTrue(progress(duel.caster()).getActiveCast().getClipStartMillis() == HALF_WARMUP,
                    "承接起手没有减半");
        }).thenExecute(duel::done).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void pullWindowExpiresAfterHalfSecond(GameTestHelper test) {
        // 从实际抽回命中的刻算起，多一刻只允许普通绕。
        var duel = duel(test);
        duel.bind().thenWaitUntil(() -> duel.pulled("right_forearm"))
                .thenIdle(WINDOW_TICKS + 1).thenExecute(() -> {
                    test.assertFalse(accepted(follow(duel.caster())), "抽回超过半秒仍可承接");
                    test.assertTrue(accepted(cast(duel.caster(), WIND, "normal")), "窗外普通绕被拒");
                    test.assertTrue(progress(duel.caster()).getActiveCast().getClipStartMillis() == 0, "普通绕跳过起手");
                }).thenExecute(duel::done).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void dispelClearsBothAndDoesNotGrantPull(GameTestHelper test) {
        // 真实牛奶和图腾应保留图标；单摘目标状态须立即解开另一端。
        var duel = duel(test);
        duel.bind().thenExecute(() -> {
            assertCuresKeep(test, duel.target(), COILED);
            assertCuresKeep(test, duel.caster(), COILING);
            duel.target().removeEffect(TechniqueStatusBenefits.get(COILED).effect());
            duel.cleared();
        }).thenIdle(LOCK_TICKS).thenExecute(() -> {
            test.assertTrue(duel.pulls().isEmpty(), "被驱散后仍抽伤前臂");
            test.assertFalse(accepted(follow(duel.caster())), "被驱散后仍能承接");
        }).thenExecute(duel::done).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void walkingStopsAtLeashAndTeleportBreaksIt(GameTestHelper test) {
        // 正常行走可退到三格边界；强制移位越界后两端松开。
        var duel = duel(test);
        duel.bind().thenExecute(() -> {
            var target = duel.target();
            target.move(MoverType.SELF, new Vec3(0, 0, OUTSIDE));
            test.assertTrue(duel.caster().distanceTo(target) == MAX_DISTANCE, "行走没有停在三格");
            test.assertTrue(has(target, COILED), "三格边界提前松开");
            var packet = new ServerboundMovePlayerPacket.Pos(target.getX(), target.getY(), target.getZ() + 1,
                    target.onGround(), target.horizontalCollision);
            target.connection.handleMovePlayer(packet);
            test.assertTrue(duel.caster().distanceTo(target) == MAX_DISTANCE, "玩家位置包越过牵制距离");
            target.setPos(target.position().add(0, 0, 1));
        }).thenWaitUntil(duel::cleared).thenExecute(() -> test.assertTrue(duel.pulls().isEmpty(), "脱离仍抽伤"))
                .thenExecute(duel::done).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void casterDeathImmediatelyReleasesTarget(GameTestHelper test) {
        // 走原版死亡入口，当场检查目标一侧，不依赖下一次移动或攻击。
        var duel = duel(test);
        duel.bind().thenExecute(() -> {
            duel.caster().setHealth(0);
            duel.caster().die(duel.caster().damageSources().genericKill());
            duel.cleared();
        }).thenExecute(duel::done).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void casterStaggerReleasesBoth(GameTestHelper test) {
        // 在有效段施加正式受击僵直，双方同时解除，后续没有末击。
        var duel = duel(test);
        duel.bind().thenExecute(() -> {
            var hit = new TechniqueService.Stagger(STAGGER_MILLIS, STAGGER_MOVEMENT, TechniqueService.StaggerKind.HIT);
            TechniqueFabric.service().stagger(duel.caster().getUUID(), hit);
            duel.cleared();
        }).thenIdle(LOCK_TICKS).thenExecute(() -> test.assertTrue(duel.pulls().isEmpty(), "踉跄后仍抽伤"))
                .thenExecute(duel::done).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void strongerForearmPullsCasterOffBalance(GameTestHelper test) {
        // 用权威组织曲线把目标的臂力提高到反拉边界，读取踉跄与真实效果。
        var duel = duel(test);
        strengthen(duel.target());
        duel.start().thenWaitUntil(() -> {
            long remaining = TechniqueFabric.service().staggerRemainingMillis(duel.caster().getUUID());
            test.assertTrue(remaining > 0, "达到五成出力差却没有反拉踉跄");
            duel.cleared();
        }).thenIdle(LOCK_TICKS).thenExecute(() -> test.assertTrue(duel.cuts().isEmpty(), "反拉后仍有割伤"))
                .thenExecute(duel::done).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void goldCastCannotBeBound(GameTestHelper test) {
        // 真正持兵且有前臂的人挂上铸金，牵制和抽回都不应发生。
        var duel = duel(test);
        var statue = TechniqueStatusBenefits.get("meridian_piglin:gold_cast").effect();
        duel.target().addEffect(new MobEffectInstance(statue, TIMEOUT));
        duel.start().thenIdle(LOCK_TICKS).thenExecute(duel::cleared)
                .thenIdle(WINDOW_TICKS + 1).thenExecute(() -> {
                    test.assertTrue(duel.cuts().isEmpty(), "铸金时仍有割伤");
                    test.assertFalse(accepted(follow(duel.caster())), "铸金目标给了抽回承接");
                }).thenExecute(duel::done).thenSucceed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void armedTargetWithoutForearmCannotBeBound(GameTestHelper test) {
        // 无身体前臂的原版生物也可装备剑，须按部位检查，不能只认装备槽。
        var duel = duel(test);
        var target = EntityTypes.COW.create(test.getLevel(), EntitySpawnReason.COMMAND);
        target.setPos(duel.target().position());
        target.setNoAi(true /* aiDisabled */);
        target.setNoGravity(true /* noGravity */);
        target.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GOLDEN_SWORD));
        test.getLevel().addFreshEntity(target);
        duel.target().setPos(duel.target().position().add(OUTSIDE, 0, 0));
        float health = target.getHealth();
        duel.start().thenIdle(LOCK_TICKS).thenExecute(() -> {
            duel.cleared();
            test.assertFalse(target.hasEffect(TechniqueStatusBenefits.get(COILED).effect()), "没有前臂也被缠住");
        }).thenIdle(WINDOW_TICKS + 1).thenExecute(() -> {
            test.assertTrue(target.getHealth() == health, "挥空在末击重新索敌");
            test.assertTrue(duel.caster().getMainHandItem().getDamageValue() == 1, "挥空没有结算末击耐久");
        }).thenExecute(duel::done).thenSucceed();
    }

    private static void strengthen(ServerPlayer player) {
        // 给实际手臂组织各登记同一增力曲线，出力仍由 Damage 实算。
        var host = TechniqueFabric.service().statusHost(player.getUUID()).orElseThrow();
        var body = VanillaDamageFabric.inspectBody(player).orElseThrow();
        var first = CurvePoint.newBuilder().setValue(1).build();
        var end = first.toBuilder().setOffsetMillis(FORCE_DURATION).build();
        var activity = BodyActivity.newBuilder();
        for (var tissue : body.getStructure().getTissuesList()) {
            if (tissue.getTagsList().contains("human:arm_muscle")) {
                var modifier = CapabilityModifier.newBuilder()
                        .setId("meridian_technique:cast/6162/" + activity.getAddModifiersCount())
                        .setSource(TechniqueHost.CALLER_ID)
                        .setTissueId(tissue.getId())
                        .setStartMillis(body.getSettledAt().getElapsedMillis())
                        .addCurve(first)
                        .addCurve(end)
                        .setStrengthPeak(COUNTER_FORCE)
                        .setBurstPeak(1)
                        .setEndurancePeak(1)
                        .setRecoveryPeak(1)
                        .build();
                activity.addAddModifiers(modifier);
            }
        }
        host.submit(new TechniqueHost.Submission("strong_arm", body.getRevision(), activity.build()));
    }
}
