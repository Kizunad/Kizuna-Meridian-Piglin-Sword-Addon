package dev.kizuna.meridian.piglinsword;

import dev.kizuna.meridian.humanoid.fabric.HumanoidHealthHost;
import dev.kizuna.meridian.humanoid.fabric.HumanoidSwordForms;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.protocol.QiDensity;
import dev.kizuna.meridian.protocol.pb.BodyMessages.QiPhase;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQi;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQiMode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.world.TechniqueStatusBenefits;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.ai.attributes.Attributes;
import dev.kizuna.meridian.piglinsword.test.SwordTestBodies;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class PiglinSwordDesperationGameTest {
    private static final String PREFIX = "meridian_piglin_sword:";
    private static final String SPLASH = PREFIX + "gold_splash";
    private static final double DYING = 0.25;
    private static final double THRESHOLD = 0.30;
    private static final float BEHIND = 180;
    private static final int RECOVERY_TICKS = 12;
    private static final int DUEL_TIMEOUT = 100;
    private static final double TARGET_DISTANCE = 2;
    private static final double OUT_OF_REACH = 5;

    private static final BlockPos START = new BlockPos(2, 2, 2);

    private record Duel(Piglin caster, Mob target, double initialTissueHp) { }

    @GameTest
    public void dyingSwordsmanSplashesShieldTarget(GameTestHelper test) {
        // 同时满足泼金和普通剑式条件，核对真正耗剑和热伤。
        var duel = duel(test, DYING);
        heat(duel.caster());
        startSplash(test, duel);
        test.succeedWhen(() -> released(test, duel));
    }

    @GameTest(maxTicks = DUEL_TIMEOUT)
    public void managedSwordsmanSplashesCurrentEnemy(GameTestHelper test) {
        // 生人大脑自己决策，卫道士从真实已学下卷中择式。
        var original = PiglinSwordSourcesGameTest.born(test, EntityTypes.VINDICATOR,
                Items.GOLDEN_SWORD, HumanoidTier.TRUE_QI);
        var caster = SwordTestBodies.atHealth(original, EntityTypes.VINDICATOR, DYING);
        caster.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GOLDEN_SWORD));
        var service = TechniqueFabric.service();
        service.learn(caster.getUUID(), PREFIX + "upper");
        service.learn(caster.getUUID(), PREFIX + "lower");
        fund(test, caster);
        var target = test.spawn(EntityTypes.VILLAGER, START);
        target.setNoAi(true /* noAi */);
        target.setNoGravity(true /* noGravity */);
        target.setPos(caster.position().add(0, 0, TARGET_DISTANCE));
        caster.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0);
        caster.setTarget(target);
        caster.setYRot(0);
        heat(caster);
        test.succeedWhen(() -> {
            test.assertTrue(caster.getMainHandItem().isEmpty(), "生人大脑尚未泼出热剑");
            test.assertTrue(target.hasEffect(TechniqueStatusBenefits.get(PREFIX + "gold_caked").effect()),
                    "生人的当前目标未挂金");
        });
    }

    @GameTest
    public void fullHealthKeepsHotSword(GameTestHelper test) {
        // 低血量以外的条件齐全时，满血仍选择普通剑式。
        holdsSword(test, 1);
    }

    @GameTest
    public void thresholdKeepsHotSword(GameTestHelper test) {
        // 刚到门槛仍保留兵器。
        holdsSword(test, THRESHOLD);
    }

    private static void holdsSword(GameTestHelper test, double ratio) {
        // 给同一套对手、武器和费用，仅改变部位生命。
        var duel = duel(test, ratio);
        heat(duel.caster());
        expectOtherForm(test, duel.caster());
        test.succeed();
    }

    @GameTest
    public void dyingColdSwordKeepsFighting(GameTestHelper test) {
        // 冷剑仍可施软剑上卷。
        var duel = duel(test, DYING);
        expectOtherForm(test, duel.caster());
        test.succeed();
    }

    @GameTest
    public void dyingNeedsCurrentTargetInSector(GameTestHelper test) {
        // 分别移除目标、背向和越距，防止择式自己创造扇区条件。
        var duel = duel(test, DYING);
        var caster = duel.caster();
        heat(caster);
        caster.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
        test.assertFalse(HumanoidSwordForms.tryCast(caster), "没有当前目标却起式");
        caster.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, duel.target());
        caster.setYRot(BEHIND);
        expectOtherForm(test, caster);
        var distant = duel(test, DYING);
        heat(distant.caster());
        distant.target().setPos(distant.caster().position().add(0, 0, OUT_OF_REACH));
        HumanoidSwordForms.tryCast(distant.caster());
        test.assertFalse(SPLASH.equals(activeForm(distant.caster())), "对扇区外的远处目标泼金");
        test.succeed();
    }

    @GameTest
    public void interruptedSplashCanRetry(GameTestHelper test) {
        // 正式中断起手，再用同一把热剑重试。
        var duel = duel(test, DYING);
        var caster = duel.caster();
        heat(caster);
        startSplash(test, duel);
        var service = TechniqueFabric.service();
        var active = service.observe(caster.getUUID()).orElseThrow().cast().orElseThrow().active();
        test.assertTrue(service.interruptWarmup(caster.getUUID(), active.getRequest().getCastId()), "没有打断起手");
        startSplash(test, duel);
        test.succeedWhen(() -> released(test, duel));
    }

    @GameTest(maxTicks = DUEL_TIMEOUT)
    public void freshHotSwordAfterReloadCannotSplashAgain(GameTestHelper test) {
        // 收势后重载同一实体，补资并给一把新的热剑。
        var duel = duel(test, DYING);
        heat(duel.caster());
        startSplash(test, duel);
        test.startSequence().thenWaitUntil(() -> released(test, duel)).thenExecuteAfter(RECOVERY_TICKS, () -> {
            var caster = SwordTestBodies.atHealth(duel.caster(), EntityTypes.PIGLIN, DYING);
            caster.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GOLDEN_SWORD));
            prepare(test, caster, duel.target());
            heat(caster);
            expectOtherForm(test, caster);
        }).thenSucceed();
    }

    private static Duel duel(GameTestHelper test, double ratio) {
        // 按真炁档出生并恢复指定伤势，持盾者固定在正前方。
        var original = PiglinSwordSourcesGameTest.born(test, EntityTypes.PIGLIN,
                Items.GOLDEN_SWORD, HumanoidTier.TRUE_QI);
        var caster = SwordTestBodies.atHealth(original, EntityTypes.PIGLIN, ratio);
        var target = PiglinSwordSourcesGameTest.shieldTarget(test, caster);
        prepare(test, caster, target);
        return new Duel(caster, target, tissueHp(target));
    }

    private static void prepare(GameTestHelper test, Piglin caster, Mob target) {
        // 用原版愤怒与攻击记忆指定同一名对手。
        fund(test, caster);
        caster.getBrain().setMemory(MemoryModuleType.ANGRY_AT, target.getUUID());
        caster.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, target);
        caster.setYRot(0);
    }

    private static void fund(GameTestHelper test, Mob caster) {
        // 只装填经脉，施式仍由目录校验、绑定和扣除。
        var qi = DebugQi.newBuilder()
                .setMode(DebugQiMode.FILL_LUMENS)
                .setDensity(QiDensity.baseline(QiPhase.GAS))
                .build();
        test.assertTrue(HumanoidHealthHost.debugQi(caster, qi) == FailureCode.FAILURE_CODE_UNSPECIFIED, "经脉补元失败");
    }

    private static void heat(Mob caster) {
        // 让正式热兵规则绑定当前手里的金剑。
        TechniqueStatusBenefits.apply(caster, TechniqueStatusBenefits.get(PREFIX + "molten").benefit());
    }

    private static String activeForm(Mob caster) {
        // 读取功法服务接受的实际招式。
        return TechniqueFabric.service().observe(caster.getUUID()).orElseThrow().cast()
                .map(forecast -> forecast.active().getRequest().getTechnique().getId()).orElse("");
    }

    private static void expectOtherForm(GameTestHelper test, Piglin caster) {
        // 盾的举起有延迟，普通剑式应按当刻的防守状态选择。
        test.assertTrue(HumanoidSwordForms.tryCast(caster), "有盾目标应可起绕");
        var actual = activeForm(caster);
        test.assertTrue(actual.equals(PREFIX + "wind_around") || actual.equals(PREFIX + "quiver"),
                "预期普通剑式，实际 " + actual);
        test.assertTrue(caster.getMainHandItem().is(Items.GOLDEN_SWORD), "失去了金剑");
    }

    private static void startSplash(GameTestHelper test, Duel duel) {
        // 请求由择式入口提交，同时检查起手尚未耗剑。
        var caster = duel.caster();
        test.assertTrue(HumanoidSwordForms.tryCast(caster), "濒死未起式");
        var actual = activeForm(caster);
        test.assertTrue(SPLASH.equals(actual), "濒死持热剑应优先泼金，实际 " + actual);
        test.assertTrue(caster.getMainHandItem().is(Items.GOLDEN_SWORD), "起手就耗剑");
    }

    private static double tissueHp(Mob target) {
        // 读取权威伤害回执写入的组织生命。
        return VanillaDamageFabric.inspectBody(target).orElseThrow().getTissueStatesList().stream()
                .mapToDouble(tissue -> tissue.getHp()).sum();
    }

    private static void released(GameTestHelper test, Duel duel) {
        // 在同一帧核对消耗、身体组织损伤和挂金状态。
        test.assertTrue(duel.caster().getMainHandItem().isEmpty(), "尚未泼出整把热剑");
        test.assertTrue(tissueHp(duel.target()) < duel.initialTissueHp(), "泼金未损伤目标组织");
        test.assertTrue(duel.target().hasEffect(TechniqueStatusBenefits.get(PREFIX + "gold_caked").effect()),
                "扇区内举盾目标未受泼金伤害或未挂金");
    }
}
