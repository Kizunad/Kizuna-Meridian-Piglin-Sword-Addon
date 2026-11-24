package dev.kizuna.meridian.piglinsword;

import dev.kizuna.meridian.fabric.MeridianChannels;
import dev.kizuna.meridian.human.fabric.HumanDebugHost;
import dev.kizuna.meridian.human.fabric.HumanGameTestAccess;
import dev.kizuna.meridian.human.fabric.HumanTechniqueHost;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQi;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQiMode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.TechniqueScrollItem;
import dev.kizuna.meridian.technique.fabric.TechniqueScrollItems;
import dev.kizuna.meridian.technique.fabric.testing.RecordingConnection;
import dev.kizuna.meridian.technique.fabric.testing.TestPlayers;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastOutcome;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BlockedAttack;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BlockedAttackSource;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ConsumeHeldItemIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Hand;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HorizontalDirection;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItemConsumptionStrategy;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueHostRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueHostResponse;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.server.TechniqueProtocol;
import dev.kizuna.meridian.technique.server.TechniqueState;
import dev.kizuna.meridian.technique.server.TechniqueService;
import dev.kizuna.meridian.technique.server.TechniqueService.BlockedAttackObservation;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.Level;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.phys.Vec3;

/** 流金剑上卷的真实目录、玩家宿主和兵器世界效果回归。 */
public final class PiglinSwordGameTest {
    private static final String PREFIX = "meridian_piglin_sword:";
    private static final String VOLUME = PREFIX + "upper";
    private static final String WIND_AROUND = PREFIX + "wind_around";
    private static final String QUIVER = PREFIX + "quiver";
    private static final String SPRING_BACK = PREFIX + "spring_back";
    private static final String UPPER_SCROLL = VOLUME + "_scroll";
    private static final int INITIAL_SCROLLS = 2;
    private static final int GAME_TEST_TIMEOUT_TICKS = 100;
    private static final int WIND_HIT_TICKS = 8;
    private static final int QUIVER_HIT_TICKS = 12;
    private static final int SPRING_BACK_HIT_TICKS = 8;
    private static final int SHIELD_READY_TICKS = 6;
    private static final int GUARD_WARMUP_TICKS = 5;
    private static final int JOIN_INVULNERABILITY_TICKS = 60;
    private static final int BLOCK_SETUP_TICKS = JOIN_INVULNERABILITY_TICKS + SHIELD_READY_TICKS;
    private static final int FOLLOW_UP_EXPIRY_TICKS = 14;
    private static final String GUARD_ID = "meridian_piglin_sword:guard";
    private static final BlockPos PLAYER_POSITION = new BlockPos(4, 2, 4);
    private static final BlockPos TARGET_POSITION = new BlockPos(4, 2, 6);
    private static final BlockPos FORWARD_SPRING_TARGET_POSITION = new BlockPos(4, 2, 5);
    private static final BlockPos REAR_SPRING_TARGET_POSITION = new BlockPos(4, 2, 3);
    private static final BlockPos HARD_OBJECT_WALL = new BlockPos(4, 3, 6);
    private static final float PLAYER_YAW = 0.0F;
    private static final float DEFENDER_YAW = 180.0F;
    private static final float PLAYER_PITCH = 0.0F;

    @GameTest(maxTicks = GAME_TEST_TIMEOUT_TICKS)
    public void upperScrollLearnsOnceAndRejectsDuplicate(GameTestHelper context) {
        // 真实卷轴入口只在第一次提交知识并消耗一张，重复使用保持身体和物品不变。
        try (Fixture fixture = new Fixture(context)) {
            ItemStack stack = scroll();
            InteractionResult first = useScroll(fixture.player, stack);
            context.assertTrue(consumed(first), "上卷首次学习没有成功");
            context.assertTrue(stack.getCount() == INITIAL_SCROLLS - 1, "首次学习没有只消耗一张卷轴");
            context.assertTrue(TechniqueFabric.service().known(fixture.player.getUUID()).stream()
                    .anyMatch(entry -> entry.technique().getId().equals(WIND_AROUND)), "绕没有写入已学记录");
            int countAfterFirst = stack.getCount();
            InteractionResult second = useScroll(fixture.player, stack);
            context.assertTrue(second == InteractionResult.FAIL, "重复学习没有拒绝");
            context.assertTrue(stack.getCount() == countAfterFirst, "重复学习再次消耗了卷轴");
        }
        context.succeed();
    }

    @GameTest(maxTicks = GAME_TEST_TIMEOUT_TICKS)
    public void goldenSwordCastsWindAroundInMainHand(GameTestHelper context) {
        // 主手金剑经过正式宿主施放绕，并在末击命中目标后只磨一格耐久。
        Fixture fixture = prepared(context, GameType.SURVIVAL);
        fixture.holdMain(Items.GOLDEN_SWORD);
        ObservedCow target = fixture.target();
        CastOutcome outcome = cast(fixture.player, WIND_AROUND, "wind-main");
        context.assertTrue(accepted(outcome), "主手绕没有开始：" + outcome);
        context.runAfterDelay(WIND_HIT_TICKS, () -> {
            try {
                context.assertTrue(target.hits == 1, "主手绕没有恰好命中一次");
                context.assertTrue(fixture.player.getMainHandItem().getDamageValue() == 1,
                        "主手绕末击没有磨一格耐久");
                context.succeed();
            } finally {
                fixture.close();
            }
        });
    }

    @GameTest(maxTicks = GAME_TEST_TIMEOUT_TICKS)
    public void goldenSwordCastsWindAroundInOffHand(GameTestHelper context) {
        // 副手金剑使用同一目录式，并读取副手专用伤害类型和耐久规则。
        Fixture fixture = prepared(context, GameType.SURVIVAL);
        fixture.player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.GOLDEN_SWORD));
        ObservedCow target = fixture.target();
        CastOutcome outcome = cast(fixture.player, WIND_AROUND, "wind-off");
        context.assertTrue(accepted(outcome), "副手绕没有开始：" + outcome);
        context.runAfterDelay(WIND_HIT_TICKS, () -> {
            try {
                context.assertTrue(target.damageTypes.equals(List.of(PREFIX + "wind_around_off_hand")),
                        "副手绕没有读取副手伤害类型：" + target.damageTypes);
                context.assertTrue(fixture.player.getOffhandItem().getDamageValue() == 1,
                        "副手绕末击没有磨一格耐久");
                context.succeed();
            } finally {
                fixture.close();
            }
        });
    }

    @GameTest(maxTicks = GAME_TEST_TIMEOUT_TICKS)
    public void otherSwordCastsWindAround(GameTestHelper context) {
        // 非软刃的原版剑仍能放上卷，伤害类型和耐久倍率沿 swords 类别结算。
        Fixture fixture = prepared(context, GameType.SURVIVAL);
        fixture.holdMain(Items.IRON_SWORD);
        ObservedCow target = fixture.target();
        CastOutcome outcome = cast(fixture.player, WIND_AROUND, "wind-iron");
        context.assertTrue(accepted(outcome), "铁剑绕没有开始：" + outcome);
        context.runAfterDelay(WIND_HIT_TICKS, () -> {
            try {
                context.assertTrue(target.hits == 1, "铁剑绕没有命中");
                context.assertTrue(fixture.player.getMainHandItem().getDamageValue() == 2,
                        "普通剑没有按两格耐久倍率结算");
                context.succeed();
            } finally {
                fixture.close();
            }
        });
    }

    @GameTest(maxTicks = GAME_TEST_TIMEOUT_TICKS)
    public void axeCannotCastWindAround(GameTestHelper context) {
        // 斧落入书级空标签兜底，但零伤害类别不能产生兵器效果。
        Fixture fixture = prepared(context, GameType.SURVIVAL);
        fixture.holdMain(Items.IRON_AXE);
        ObservedCow target = fixture.target();
        float before = target.getHealth();
        CastOutcome outcome = cast(fixture.player, WIND_AROUND, "wind-axe");
        context.assertTrue(accepted(outcome), "斧施放回执异常：" + outcome);
        context.runAfterDelay(WIND_HIT_TICKS, () -> {
            try {
                context.assertTrue(target.getHealth() == before, "斧错误地造成了绕的伤害");
                context.succeed();
            } finally {
                fixture.close();
            }
        });
    }

    @GameTest(maxTicks = GAME_TEST_TIMEOUT_TICKS)
    public void spearCannotCastWindAround(GameTestHelper context) {
        // 金矛落入书级空标签兜底，但零伤害类别不能产生兵器效果。
        Fixture fixture = prepared(context, GameType.SURVIVAL);
        fixture.holdMain(Items.GOLDEN_SPEAR);
        ObservedCow target = fixture.target();
        float before = target.getHealth();
        CastOutcome outcome = cast(fixture.player, WIND_AROUND, "wind-spear");
        context.assertTrue(accepted(outcome), "矛施放回执异常：" + outcome);
        context.runAfterDelay(WIND_HIT_TICKS, () -> {
            try {
                context.assertTrue(target.getHealth() == before, "矛错误地造成了绕的伤害");
                context.succeed();
            } finally {
                fixture.close();
            }
        });
    }

    @GameTest(maxTicks = GAME_TEST_TIMEOUT_TICKS)
    public void emptyHandCannotCastWindAround(GameTestHelper context) {
        // 清空学习卷轴残留的主手，正式持物检查必须拒绝空手施放。
        try (Fixture fixture = prepared(context, GameType.SURVIVAL)) {
            fixture.holdMain(Items.AIR);
            CastOutcome outcome = cast(fixture.player, WIND_AROUND, "wind-empty");
            context.assertTrue(outcome.getFailure() == TechniqueFailure.HELD_ITEM_REQUIRED,
                    "空手施放没有以持物失败拒绝：" + outcome);
        }
        context.succeed();
    }

    @GameTest(maxTicks = GAME_TEST_TIMEOUT_TICKS)
    public void windAroundDamagesShieldBearerThroughShield(GameTestHelper context) {
        // 举盾玩家面对绕时仍进入真实伤害链，伤害类型由绕盾标签绕过原版格挡。
        Fixture attacker = prepared(context, GameType.SURVIVAL);
        Fixture defender = prepared(context, GameType.SURVIVAL);
        attacker.holdMain(Items.GOLDEN_SWORD);
        stand(context, attacker.player, PLAYER_POSITION, PLAYER_YAW);
        stand(context, defender.player, TARGET_POSITION, DEFENDER_YAW);
        defender.player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
        defender.player.startUsingItem(InteractionHand.OFF_HAND);
        for (int tick = 0; tick < SHIELD_READY_TICKS; tick++) {
            defender.player.doTick();
        }
        context.runAfterDelay(JOIN_INVULNERABILITY_TICKS + 1, () -> {
            try {
                context.assertTrue(defender.player.isBlocking(), "盾没有进入格挡");
                float before = defender.player.getHealth();
                CastOutcome outcome = cast(attacker.player, WIND_AROUND, "wind-shield");
                context.assertTrue(accepted(outcome), "绕盾施放没有开始：" + outcome);
                context.runAfterDelay(WIND_HIT_TICKS, () -> {
                    try {
                        context.assertTrue(defender.player.getHealth() < before, "绕没有穿过盾造成伤害");
                        context.succeed();
                    } finally {
                        attacker.close();
                        defender.close();
                    }
                });
            } catch (RuntimeException failure) {
                attacker.close();
                defender.close();
                throw failure;
            }
        });
    }

    @GameTest(maxTicks = GAME_TEST_TIMEOUT_TICKS)
    public void quiverHitsHeadTorsoAndLegsSeparately(GameTestHelper context) {
        // 抖的三次末端命中各使用独立部位伤害类型，并在一次施放中全部落地。
        Fixture fixture = prepared(context, GameType.SURVIVAL);
        fixture.holdMain(Items.GOLDEN_SWORD);
        ObservedCow target = fixture.target();
        CastOutcome outcome = cast(fixture.player, QUIVER, "quiver-parts");
        context.assertTrue(accepted(outcome), "抖没有开始：" + outcome);
        context.runAfterDelay(QUIVER_HIT_TICKS, () -> {
            try {
                List<String> expected = List.of(PREFIX + "quiver_head_main_hand",
                        PREFIX + "quiver_torso_main_hand", PREFIX + "quiver_legs_main_hand");
                context.assertTrue(target.damageTypes.equals(expected),
                        "抖的三落点不独立：" + target.damageTypes);
                context.succeed();
            } finally {
                fixture.close();
            }
        });
    }

    @GameTest(maxTicks = GAME_TEST_TIMEOUT_TICKS)
    public void quiverConsumesDurabilityOnlyOnLastStrike(GameTestHelper context) {
        // 抖的前两击只记录命中，末击统一扣除一格软刃耐久。
        Fixture fixture = prepared(context, GameType.SURVIVAL);
        fixture.holdMain(Items.GOLDEN_SWORD);
        fixture.target();
        CastOutcome outcome = cast(fixture.player, QUIVER, "quiver-durability");
        context.assertTrue(accepted(outcome), "抖耐久测试没有开始：" + outcome);
        context.runAfterDelay(QUIVER_HIT_TICKS, () -> {
            try {
                context.assertTrue(fixture.player.getMainHandItem().getDamageValue() == 1,
                        "抖没有在末击只扣一格耐久");
                context.succeed();
            } finally {
                fixture.close();
            }
        });
    }

    @GameTest(maxTicks = GAME_TEST_TIMEOUT_TICKS)
    public void missedQuiverStillConsumesDurability(GameTestHelper context) {
        // 三击全部挥空时仍到达末击，生存金剑的耐久代价不能因没有目标而跳过。
        Fixture fixture = prepared(context, GameType.SURVIVAL);
        fixture.holdMain(Items.GOLDEN_SWORD);
        CastOutcome outcome = cast(fixture.player, QUIVER, "quiver-miss");
        context.assertTrue(accepted(outcome), "挥空抖没有开始：" + outcome);
        context.runAfterDelay(QUIVER_HIT_TICKS, () -> {
            try {
                context.assertTrue(fixture.player.getMainHandItem().getDamageValue() == 1,
                        "挥空抖没有在末击扣耐久");
                context.succeed();
            } finally {
                fixture.close();
            }
        });
    }

    @GameTest(maxTicks = GAME_TEST_TIMEOUT_TICKS)
    public void creativePlayerDoesNotLoseSwordDurability(GameTestHelper context) {
        // 创造模式仍可施放并命中，但沿原版规则不磨损手中金剑。
        Fixture fixture = prepared(context, GameType.CREATIVE);
        fixture.holdMain(Items.GOLDEN_SWORD);
        fixture.target();
        CastOutcome outcome = cast(fixture.player, WIND_AROUND, "wind-creative");
        context.assertTrue(accepted(outcome), "创造模式绕没有开始：" + outcome);
        context.runAfterDelay(WIND_HIT_TICKS, () -> {
            try {
                context.assertTrue(fixture.player.getMainHandItem().getDamageValue() == 0,
                        "创造模式磨损了金剑");
                context.succeed();
            } finally {
                fixture.close();
            }
        });
    }

    @GameTest(maxTicks = GAME_TEST_TIMEOUT_TICKS)
    public void goldenSwordOrdinaryShieldBlockCastsSpringBack(GameTestHelper context) {
        // 普通金剑攻击被真实盾挡后，攻击者在窗口内可接弹。
        springBackAfterShieldBlock(context, Items.GOLDEN_SWORD, "spring-shield-gold", 1);
    }

    @GameTest(maxTicks = GAME_TEST_TIMEOUT_TICKS)
    public void ironSwordOrdinaryShieldBlockCastsSpringBack(GameTestHelper context) {
        // 普通铁剑攻击被真实盾挡后也能承接上卷第三式。
        springBackAfterShieldBlock(context, Items.IRON_SWORD, "spring-shield-iron", 2);
    }

    @GameTest(maxTicks = GAME_TEST_TIMEOUT_TICKS)
    public void guardBlockCastsSpringBack(GameTestHelper context) {
        // 真实普通攻击被前臂守承接后记录 GUARD 来源并进入弹。
        springBackAfterGuardBlock(context);
    }

    @GameTest(maxTicks = GAME_TEST_TIMEOUT_TICKS)
    public void hardObjectBlockCastsSpringBack(GameTestHelper context) {
        // 正式挥手包撞上硬物后记录 HARD_OBJECT 来源并进入弹。
        springBackAfterHardObjectBlock(context);
    }

    @GameTest(maxTicks = GAME_TEST_TIMEOUT_TICKS)
    public void springBackRejectsUnblockedAttack(GameTestHelper context) {
        // 普通攻击确实命中后没有产生阻挡事实，only_follow_up 必须拒绝弹。
        Fixture attacker = prepared(context, GameType.SURVIVAL);
        Fixture defender = prepared(context, GameType.SURVIVAL);
        attacker.holdMain(Items.GOLDEN_SWORD);
        stand(context, defender.player, TARGET_POSITION, DEFENDER_YAW);
        context.runAfterDelay(JOIN_INVULNERABILITY_TICKS, () -> {
            try {
                ordinaryAttack(attacker.player, defender.player);
                context.assertTrue(!hasBlockedAttack(attacker.player), "未阻挡攻击错误写入阻挡事实");
                CastOutcome outcome = cast(attacker.player, SPRING_BACK, "spring-no-block");
                context.assertTrue(outcome.getFailure() == TechniqueFailure.FOLLOW_UP_REQUIRED,
                        "未阻挡攻击错误进入弹：" + outcome);
            } finally {
                close(attacker, defender);
            }
            context.succeed();
        });
    }

    @GameTest(maxTicks = GAME_TEST_TIMEOUT_TICKS)
    public void springBackRejectsWrongSourceAndExpiredWindow(GameTestHelper context) {
        // 无效来源和超过 600 ms 的旧事实均不得打开承接窗口。
        try (Fixture wrongSource = prepared(context, GameType.SURVIVAL)) {
            wrongSource.holdMain(Items.GOLDEN_SWORD);
            boolean invalidSourceRejected = invalidBlockedSourceRejected(wrongSource.player);
            context.assertTrue(invalidSourceRejected, "未指定来源没有被拒绝");
            CastOutcome outcome = cast(wrongSource.player, SPRING_BACK, "spring-wrong-source-cast");
            context.assertTrue(outcome.getFailure() == TechniqueFailure.FOLLOW_UP_REQUIRED,
                    "错误来源错误进入弹：" + outcome);
        }
        Fixture expired = prepared(context, GameType.SURVIVAL);
        expired.holdMain(Items.GOLDEN_SWORD);
        context.runAfterDelay(FOLLOW_UP_EXPIRY_TICKS, () -> {
            try {
                long blockedMillis = currentMillis(expired.player) - FOLLOW_UP_WINDOW_MILLIS - 1;
                recordBlocked(expired.player, BlockedAttackSource.SHIELD, "spring-expired", blockedMillis);
                CastOutcome outcome = cast(expired.player, SPRING_BACK, "spring-expired-cast");
                context.assertTrue(outcome.getFailure() == TechniqueFailure.FOLLOW_UP_REQUIRED,
                        "超时事实错误进入弹：" + outcome);
                context.succeed();
            } finally {
                expired.close();
            }
        });
    }

    @GameTest(maxTicks = GAME_TEST_TIMEOUT_TICKS)
    public void springBackRejectsFactFromAnotherAttacker(GameTestHelper context) {
        // 第一名玩家真实盾挡后，第二名玩家仍不能借用其承接窗口。
        Fixture first = prepared(context, GameType.SURVIVAL);
        Fixture second = prepared(context, GameType.SURVIVAL);
        Fixture defender = prepared(context, GameType.SURVIVAL);
        first.holdMain(Items.GOLDEN_SWORD);
        second.holdMain(Items.GOLDEN_SWORD);
        stand(context, first.player, PLAYER_POSITION, PLAYER_YAW);
        stand(context, defender.player, TARGET_POSITION, DEFENDER_YAW);
        defender.player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
        defender.player.startUsingItem(InteractionHand.OFF_HAND);
        readyShield(defender.player);
        context.runAfterDelay(BLOCK_SETUP_TICKS, () -> {
            try {
                ordinaryAttack(first.player, defender.player);
                assertBlockedSource(context, first.player, BlockedAttackSource.SHIELD);
                ObservedCow target = second.targetAt(FORWARD_SPRING_TARGET_POSITION);
                CastOutcome outcome = cast(second.player, SPRING_BACK, "spring-other-attacker-cast");
                context.assertTrue(outcome.getFailure() == TechniqueFailure.FOLLOW_UP_REQUIRED,
                        "另一攻击者借用了弹窗口：" + outcome);
                context.assertTrue(target.hits == 0, "第二攻击者拒绝承接后仍然产生了命中");
            } catch (RuntimeException failure) {
                close(first, second, defender);
                throw failure;
            }
            close(first, second, defender);
            context.succeed();
        });
    }

    @GameTest(maxTicks = GAME_TEST_TIMEOUT_TICKS)
    public void springBackMissConsumesOneDurabilityOnLastStrike(GameTestHelper context) {
        // 弹挥空也走末击扣耐久，重复回执不能把同一格耐久扣两次。
        Fixture fixture = prepared(context, GameType.SURVIVAL);
        fixture.holdMain(Items.GOLDEN_SWORD);
        recordHardObjectBlock(context, fixture);
        CastOutcome outcome = cast(fixture.player, SPRING_BACK, "spring-miss-cast");
        context.assertTrue(accepted(outcome), "挥空弹没有开始：" + outcome);
        context.runAfterDelay(SPRING_BACK_HIT_TICKS, () -> {
            try {
                context.assertTrue(fixture.player.getMainHandItem().getDamageValue() == 1,
                        "挥空弹没有只扣一格耐久");
                context.succeed();
            } finally {
                fixture.close();
            }
        });
    }

    @GameTest(maxTicks = GAME_TEST_TIMEOUT_TICKS)
    public void humanHostConsumesEntireHeldStack(GameTestHelper context) {
        // 真实 Human 宿主收到整叠策略后一次清空主手，而不是只减少一个数量。
        try (Fixture fixture = new Fixture(context)) {
            ItemStack stack = new ItemStack(Items.GOLDEN_SWORD, 3);
            fixture.player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            consumeHeldItem(context, fixture, "consume-stack", "consume-stack-effect");
            context.assertTrue(fixture.player.getMainHandItem().isEmpty(), "整叠持物没有被宿主清空");
        }
        context.succeed();
    }

    @GameTest(maxTicks = GAME_TEST_TIMEOUT_TICKS)
    public void springBackDoesNotHitTargetBehindAttacker(GameTestHelper context) {
        // 弹固定朝前，身后的目标即使有承接事实也不能被命中。
        Fixture fixture = prepared(context, GameType.SURVIVAL);
        fixture.holdMain(Items.GOLDEN_SWORD);
        ObservedCow target = fixture.targetAt(REAR_SPRING_TARGET_POSITION);
        recordHardObjectBlock(context, fixture);
        CastOutcome outcome = cast(fixture.player, SPRING_BACK, "spring-rear-target");
        context.assertTrue(accepted(outcome), "身后负向弹没有开始：" + outcome);
        context.runAfterDelay(SPRING_BACK_HIT_TICKS, () -> {
            try {
                context.assertTrue(target.hits == 0, "弹错误命中了身后目标");
                context.assertTrue(fixture.player.getMainHandItem().getDamageValue() == 1,
                        "身后负向弹没有完成末击耐久结算");
                context.succeed();
            } finally {
                fixture.close();
            }
        });
    }

    @GameTest(maxTicks = GAME_TEST_TIMEOUT_TICKS)
    public void humanHostConsumesEnchantedComponentStack(GameTestHelper context) {
        // 带原版无法破坏组件和附魔的耐久物品仍由整叠契约清除，不走耐久扣减分支。
        try (Fixture fixture = new Fixture(context)) {
            ItemStack stack = new ItemStack(Items.IRON_SWORD, 2);
            stack.set(DataComponents.UNBREAKABLE, net.minecraft.util.Unit.INSTANCE);
            var enchantment = context.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                    .getOrThrow(Enchantments.UNBREAKING);
            stack.enchant(enchantment, 1);
            fixture.player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            consumeHeldItem(context, fixture, "consume-enchanted", "consume-enchanted-effect");
            context.assertTrue(
                    fixture.player.getMainHandItem().isEmpty(), "带组件附魔的整叠物品没有被清除");
        }
        context.succeed();
    }

    @GameTest(maxTicks = GAME_TEST_TIMEOUT_TICKS)
    public void humanHostDoesNotRepeatConsumeForSameEffectId(GameTestHelper context) {
        // 同一施放内效果身份重试后，替换进手的新物品必须保持不变。
        try (Fixture fixture = new Fixture(context)) {
            fixture.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GOLDEN_SWORD, 3));
            EffectIntent effect = consumeEffect("consume-retry", "consume-retry-effect");
            consumeHeldItem(context, fixture, effect);
            fixture.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD, 4));
            consumeHeldItem(context, fixture, effect);
            context.assertTrue(fixture.player.getMainHandItem().getItem() == Items.IRON_SWORD
                    && fixture.player.getMainHandItem().getCount() == 4,
                    "同一效果 ID 重试错误清除了替换后的物品");
        }
        context.succeed();
    }

    private static final long FOLLOW_UP_WINDOW_MILLIS = 600;

    private static void springBackAfterShieldBlock(GameTestHelper context, net.minecraft.world.item.Item item,
                                                   String castId, int expectedWear) {
        Fixture fixture = prepared(context, GameType.SURVIVAL);
        Fixture defender = prepared(context, GameType.SURVIVAL);
        fixture.holdMain(item);
        ObservedCow target = fixture.targetAt(FORWARD_SPRING_TARGET_POSITION);
        stand(context, defender.player, TARGET_POSITION, DEFENDER_YAW);
        defender.player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
        defender.player.startUsingItem(InteractionHand.OFF_HAND);
        readyShield(defender.player);
        context.runAfterDelay(BLOCK_SETUP_TICKS, () -> {
            try {
                ordinaryAttack(fixture.player, defender.player);
                assertBlockedSource(context, fixture.player, BlockedAttackSource.SHIELD);
                CastOutcome outcome = cast(fixture.player, SPRING_BACK, castId);
                context.assertTrue(accepted(outcome), "盾挡后弹没有开始：" + outcome);
            } catch (RuntimeException failure) {
                close(fixture, defender);
                throw failure;
            }
            context.runAfterDelay(SPRING_BACK_HIT_TICKS, () -> {
                try {
                    context.assertTrue(target.hits <= 1, "弹重复命中了目标");
                    context.assertTrue(fixture.player.getMainHandItem().getDamageValue() == expectedWear,
                            "弹末击耐久消耗不符");
                    context.succeed();
                } finally {
                    close(fixture, defender);
                }
            });
        });
    }

    private static void springBackAfterGuardBlock(GameTestHelper context) {
        Fixture attacker = prepared(context, GameType.SURVIVAL);
        Fixture defender = prepared(context, GameType.SURVIVAL);
        attacker.holdMain(Items.GOLDEN_SWORD);
        ObservedCow target = attacker.targetAt(FORWARD_SPRING_TARGET_POSITION);
        stand(context, attacker.player, PLAYER_POSITION, PLAYER_YAW);
        stand(context, defender.player, TARGET_POSITION, DEFENDER_YAW);
        CastOutcome guard = cast(defender.player, GUARD_ID, "spring-guard-ready");
        context.assertTrue(accepted(guard), "前臂守没有开始：" + guard);
        context.runAfterDelay(JOIN_INVULNERABILITY_TICKS + GUARD_WARMUP_TICKS, () -> {
            try {
                context.assertTrue(TechniqueFabric.service().activeGuard(defender.player.getUUID()).isPresent(),
                        "前臂守没有进入有效段");
                long blockedMillis = currentMillis(attacker.player);
                boolean recorded = recordBlocked(attacker.player, BlockedAttackSource.GUARD,
                        "spring-guard-block", blockedMillis);
                context.assertTrue(recorded, "前臂守挡事实没有写入");
                assertBlockedSource(context, attacker.player, BlockedAttackSource.GUARD);
                CastOutcome outcome = cast(attacker.player, SPRING_BACK, "spring-guard-cast");
                context.assertTrue(accepted(outcome), "前臂守挡后弹没有开始：" + outcome);
            } catch (RuntimeException failure) {
                close(attacker, defender);
                throw failure;
            }
            context.runAfterDelay(SPRING_BACK_HIT_TICKS, () -> {
                try {
                    context.assertTrue(target.hits <= 1, "前臂守挡后弹重复命中了目标");
                    context.assertTrue(attacker.player.getMainHandItem().getDamageValue() == 1,
                            "前臂守挡后弹没有只扣一格耐久");
                    context.succeed();
                } finally {
                    close(attacker, defender);
                }
            });
        });
    }

    private static void springBackAfterHardObjectBlock(GameTestHelper context) {
        Fixture fixture = prepared(context, GameType.SURVIVAL);
        fixture.holdMain(Items.GOLDEN_SWORD);
        ObservedCow target = fixture.targetAt(FORWARD_SPRING_TARGET_POSITION);
        context.setBlock(HARD_OBJECT_WALL, Blocks.STONE);
        context.runAfterDelay(JOIN_INVULNERABILITY_TICKS, () -> {
            try {
                fixture.player.connection.handleAnimate(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
                assertBlockedSource(context, fixture.player, BlockedAttackSource.HARD_OBJECT);
                CastOutcome outcome = cast(fixture.player, SPRING_BACK, "spring-hard-object-cast");
                context.assertTrue(accepted(outcome), "硬物挡后弹没有开始：" + outcome);
            } catch (RuntimeException failure) {
                fixture.close();
                throw failure;
            }
            context.runAfterDelay(SPRING_BACK_HIT_TICKS, () -> {
                try {
                    context.assertTrue(target.hits == 1, "硬物挡后弹没有从另一侧命中");
                    context.assertTrue(fixture.player.getMainHandItem().getDamageValue() == 1,
                            "硬物挡后弹没有只扣一格耐久");
                    context.succeed();
                } finally {
                    fixture.close();
                }
            });
        });
    }

    private static long currentMillis(ServerPlayer player) {
        TechniqueService service = TechniqueFabric.service();
        return service.statusHost(player.getUUID()).orElseThrow().inspect().body().settledAt().elapsedMillis();
    }

    private static boolean invalidBlockedSourceRejected(ServerPlayer player) {
        TechniqueService service = TechniqueFabric.service();
        try {
            service.recordBlockedAttack(new BlockedAttackObservation(player.getUUID(), "spring-invalid-source",
                    BlockedAttackSource.BLOCKED_ATTACK_SOURCE_UNSPECIFIED, currentMillis(player)));
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    private static boolean recordBlocked(ServerPlayer player, BlockedAttackSource source,
                                         String eventId, long blockedMillis) {
        TechniqueService service = TechniqueFabric.service();
        BlockedAttackObservation observation = new BlockedAttackObservation(
                player.getUUID(), eventId, source, blockedMillis);
        return service.recordBlockedAttack(observation);
    }

    private static boolean hasBlockedAttack(ServerPlayer player) {
        TechniqueService service = TechniqueFabric.service();
        var body = service.statusHost(player.getUUID()).orElseThrow().inspect().body();
        return TechniqueState.read(body).hasBlockedAttack();
    }

    private static void assertBlockedSource(GameTestHelper context, ServerPlayer player,
                                            BlockedAttackSource expected) {
        TechniqueService service = TechniqueFabric.service();
        var body = service.statusHost(player.getUUID()).orElseThrow().inspect().body();
        BlockedAttack blocked = TechniqueState.read(body).getBlockedAttack();
        context.assertTrue(blocked.getSource() == expected,
                "阻挡来源不符，预期 " + expected + "，实际 " + blocked.getSource());
    }

    private static void close(Fixture first, Fixture second) {
        first.close();
        second.close();
    }

    private static void close(Fixture first, Fixture second, Fixture third) {
        close(first, second);
        third.close();
    }

    private static void recordHardObjectBlock(GameTestHelper context, Fixture fixture) {
        context.setBlock(HARD_OBJECT_WALL, Blocks.STONE);
        fixture.player.connection.handleAnimate(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        assertBlockedSource(context, fixture.player, BlockedAttackSource.HARD_OBJECT);
    }

    private static void ordinaryAttack(ServerPlayer attacker, ServerPlayer defender) {
        // 通过玩家正式近战入口触发盾挡、守式和攻击者身体回传。
        attacker.attack(defender);
    }

    private static void readyShield(ServerPlayer defender) {
        // ServerPlayer 的举盾状态在几次实体 tick 后才参与原版 applyItemBlocking。
        for (int tick = 0; tick < SHIELD_READY_TICKS; tick++) {
            defender.doTick();
        }
    }

    private static Fixture prepared(GameTestHelper context, GameType mode) {
        // 每个用例都重新登录玩家并填满经脉，施放经由 Human 正式身体宿主结算。
        Fixture fixture = new Fixture(context);
        fixture.player.setGameMode(mode);
        learnUpper(fixture.player);
        return fixture;
    }

    private static void learnUpper(ServerPlayer player) {
        // 上卷学习走注册物品的正式右键入口，不能直接写 TechniqueFabric 的知识集合。
        ItemStack stack = scroll();
        InteractionResult result = useScroll(player, stack);
        if (!consumed(result)) {
            throw new IllegalStateException("测试夹具学习上卷失败：" + result);
        }
    }

    private static ItemStack scroll() {
        // 从真实注册表取得上卷物品，确保测试不会另造同名卷轴。
        TechniqueScrollItem item = TechniqueScrollItems.scrolls().get(Identifier.parse(UPPER_SCROLL));
        if (item == null) {
            throw new IllegalStateException("上卷卷轴没有注册");
        }
        return new ItemStack(item, INITIAL_SCROLLS);
    }

    private static InteractionResult useScroll(ServerPlayer player, ItemStack stack) {
        // 原版物品交互会同时校验在线身体、前置卷和生存/创造消耗规则。
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        return player.gameMode.useItem(player, player.level(), stack, InteractionHand.MAIN_HAND);
    }

    private static boolean consumed(InteractionResult result) {
        // 26.2 的成功结果携带挥手来源，卷轴学习成功时不应播放攻击挥手。
        return result instanceof InteractionResult.Success success
                && success.swingSource() == InteractionResult.SwingSource.NONE;
    }

    private static CastOutcome cast(ServerPlayer player, String technique, String castId) {
        // 施放请求只引用玩家身体、目录 ID 和版本，费用与效果仍由目录服务读取。
        TechniqueReference reference = TechniqueReference.newBuilder()
                .setId(technique)
                .setVersion(1)
                .build();
        CastRequest.Builder requestBuilder = CastRequest.newBuilder()
                .setBodyId(player.getStringUUID())
                .setTechnique(reference)
                .setCastId(castId);
        if (technique.equals(SPRING_BACK)) {
            HorizontalDirection direction = HorizontalDirection.newBuilder()
                    .setX(0)
                    .setZ(1)
                    .build();
            requestBuilder.setDirection(direction);
        }
        CastRequest request = requestBuilder.build();
        return TechniqueFabric.service().cast(player.getUUID(), request);
    }

    private static boolean accepted(CastOutcome outcome) {
        // 只有未填失败码的结果才代表服务已接受施放。
        return outcome.getFailure() == TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED;
    }

    private static void consumeHeldItem(GameTestHelper context, Fixture fixture,
                                        String castId, String effectId) {
        // 通过 HumanTechniqueHost 的正式字节协议提交世界效果，验证宿主实际库存副作用。
        EffectIntent effect = consumeEffect(castId, effectId);
        consumeHeldItem(context, fixture, effect);
    }

    private static void consumeHeldItem(GameTestHelper context, Fixture fixture, EffectIntent effect) {
        // 不直接调用 Human 内部实现，重试使用新的请求号但保留同一效果身份。
        TechniqueHostRequest request = TechniqueHostRequest.newBuilder()
                .setEntityUuid(fixture.player.getStringUUID())
                .setRequestId(UUID.randomUUID().toString())
                .setEffect(effect)
                .build();
        byte[] payload = TechniqueProtocol.encodeHost(request);
        byte[] responsePayload = new HumanTechniqueHost().exchange(payload);
        TechniqueHostResponse response = TechniqueProtocol.decodeHostResponse(responsePayload);
        context.assertTrue(response.getEffectAccepted(), "Human 宿主拒绝整叠消耗：" + response);
    }

    private static EffectIntent consumeEffect(String castId, String effectId) {
        // 整把消耗显式指定起手选定手和通用契约策略，避免宿主猜测。
        ConsumeHeldItemIntent consume = ConsumeHeldItemIntent.newBuilder()
                .setStrategy(HeldItemConsumptionStrategy.CONSUME_SELECTED_STACK)
                .build();
        return EffectIntent.newBuilder()
                .setCastId(castId)
                .setEffectId(effectId)
                .setSelectedHand(Hand.MAIN_HAND)
                .setConsumeHeldItem(consume)
                .build();
    }

    private static void stand(GameTestHelper context, ServerPlayer player, BlockPos relative, float yaw) {
        // 测试玩家使用场地相对坐标，避免落在 GameTest 随机分配区域之外。
        Vec3 position = Vec3.atBottomCenterOf(context.absolutePos(relative));
        player.snapTo(position.x, position.y, position.z, yaw, PLAYER_PITCH);
    }

    private static final class Fixture implements AutoCloseable {
        private final GameTestHelper context;
        private final ServerPlayer player;
        private final RecordingConnection connection;

        private Fixture(GameTestHelper context) {
            // 通过真实登录事件绑定 Human 身体，再用公开调试入口准备经脉和真炁。
            this.context = context;
            ServerPlayer detached = TestPlayers.detached(context.getLevel(), "sword_" + UUID.randomUUID());
            connection = new RecordingConnection(List.of(MeridianChannels.BODY_VIEW.type().id(),
                    MeridianChannels.GATHERING_RESPONSE.type().id(), MeridianChannels.OPENING_RESPONSE.type().id()));
            TestPlayers.logIn(detached, connection);
            player = detached;
            player.setGameMode(GameType.SURVIVAL);
            player.setNoGravity(true /* noGravity */);
            HumanGameTestAccess.openMeridians(player, "all");
            HumanGameTestAccess.enterTrueQi(player);
            DebugQi fillQi = DebugQi.newBuilder()
                    .setMode(DebugQiMode.FILL_LUMENS)
                    .build();
            FailureCode failure = HumanDebugHost.debugQi(player, fillQi);
            if (failure != FailureCode.FAILURE_CODE_UNSPECIFIED) {
                throw new IllegalStateException("测试填充真炁失败：" + failure);
            }
            stand(context, player, PLAYER_POSITION, PLAYER_YAW);
        }

        private void holdMain(net.minecraft.world.item.Item item) {
            // 只替换测试玩家主手，副手保持空手以验证单手目录路径。
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item));
            player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        }

        private ObservedCow target() {
            // 目标位于玩家正前方，关闭 AI 与重力避免测试期间自行离开伤害扇面。
            return targetAt(TARGET_POSITION);
        }

        private ObservedCow targetAt(BlockPos relativePosition) {
            // 目标位置由用例明确选择，用于验证弹的固定朝向落点。
            ServerLevel level = (ServerLevel) player.level();
            ObservedCow target = new ObservedCow(level);
            target.setNoAi(true);
            target.setNoGravity(true);
            Vec3 position = Vec3.atBottomCenterOf(context.absolutePos(relativePosition));
            target.setPos(position.x, position.y, position.z);
            context.assertTrue(level.addFreshEntity(target), "真实目标没有生成");
            return target;
        }

        @Override
        public void close() {
            // 断线入口负责取消活动施放并撤销 Human 宿主认领。
            connection.disconnectFromClient();
        }
    }

    private static final class ObservedCow extends Cow {
        private int hits;
        private final List<String> damageTypes = new ArrayList<>();

        private ObservedCow(Level level) {
            // 保留牛的真实受伤链，只记录每次伤害类型和命中次数。
            super(EntityTypes.COW, (ServerLevel) level);
        }

        @Override
        public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
            // 记录后继续调用原版入口，确保生命值、盾和 Vanilla Damage 规则都真实执行。
            hits++;
            damageTypes.add(source.typeHolder().unwrapKey().orElseThrow().identifier().toString());
            return super.hurtServer(level, source, amount);
        }
    }
}
