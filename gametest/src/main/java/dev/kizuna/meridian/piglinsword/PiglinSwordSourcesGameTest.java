package dev.kizuna.meridian.piglinsword;

import dev.kizuna.meridian.humanoid.fabric.HumanoidBrain;
import dev.kizuna.meridian.humanoid.fabric.HumanoidHealthHost;
import dev.kizuna.meridian.humanoid.fabric.HumanoidSpeciesBirths;
import dev.kizuna.meridian.humanoid.fabric.HumanoidSwordForms;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.protocol.QiDensity;
import dev.kizuna.meridian.protocol.pb.BodyMessages.QiPhase;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQi;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQiMode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.TechniqueScrollDrops;
import dev.kizuna.meridian.technique.fabric.TechniqueScrollItems;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BlockedAttackSource;
import dev.kizuna.meridian.technique.server.TechniqueService.BlockedAttackObservation;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import java.util.List;
import java.util.SplittableRandom;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

public final class PiglinSwordSourcesGameTest {
    private static final String PREFIX = "meridian_piglin_sword:";
    private static final BlockPos SPAWN = new BlockPos(2, 2, 2);
    private static final int ROLLS = 512;
    private static final int MAX_RARE = 64;
    private static final int INVENTORY_SIZE = 8;
    private static final int WEAPON_ROLLS = 128;
    private static final int AI_TIMEOUT = 160;
    private static final double MELEE_RANGE = 2;
    private static final double FLOW_DISTANCE = 5;
    private static final TagKey<Item> SOFT = TagKey.create(Registries.ITEM,
            Identifier.parse(PREFIX + "soft_blades"));
    private static final String UPPER = "upper_scroll";
    private static final String LOWER = "lower_scroll";

    @GameTest
    public void bastionsRollOnlyUpper(GameTestHelper test) {
        // 四类真实箱表各自出上卷，普通地牢作为边界对照。
        var params = new LootParams.Builder(test.getLevel()).withParameter(LootContextParams.ORIGIN, Vec3.ZERO)
                .create(LootContextParamSets.CHEST);
        for (var chest : List.of("bastion_bridge", "bastion_other", "bastion_hoglin_stable", "bastion_treasure")) {
            var rolls = roll(test, "chests/" + chest, params);
            test.assertTrue(rare(rolls.upper()) && rolls.lower() == 0, chest + " 箱表卷轴异常 " + rolls);
        }
        var dungeon = roll(test, "chests/simple_dungeon", params);
        test.assertTrue(dungeon.upper() + dungeon.lower() == 0, "卷轴混入普通地牢");
        test.succeed();
    }

    @GameTest
    public void barteringAddsOnlyForPractitioners(GameTestHelper test) {
        // 从原版易货表同时抽出普通物品，保留第八格中的标记物。
        var trader = born(test, EntityTypes.PIGLIN, Items.CROSSBOW, HumanoidTier.WANDERING_QI);
        var inventory = trader.getInventory();
        test.assertTrue(inventory.getContainerSize() == INVENTORY_SIZE, "猪灵库存大小改变");
        inventory.setItem(INVENTORY_SIZE - 1, new ItemStack(Items.STICK));
        var rewards = barter(test, trader);
        test.assertTrue(rare(rewards.upper()) && rewards.lower() == 0, "修士易货卷轴异常 " + rewards);
        test.assertTrue(rewards.ordinary() == ROLLS, "额外卷池挤掉了原版易货");
        test.assertTrue(inventory.getItem(INVENTORY_SIZE - 1).is(Items.STICK), "易货动用了八格库存");
        var mortal = born(test, EntityTypes.PIGLIN, Items.GOLDEN_SWORD, HumanoidTier.MORTAL);
        var mundane = barter(test, mortal);
        test.assertTrue(mundane.upper() + mundane.lower() == 0 && mundane.ordinary() == ROLLS, "普通猪灵易货串卷");
        test.succeed();
    }

    private static Rolls barter(GameTestHelper test, Piglin piglin) {
        // 使用和原版投出易货物相同的实体上下文。
        var params = new LootParams.Builder(test.getLevel()).withParameter(LootContextParams.THIS_ENTITY, piglin)
                .create(LootContextParamSets.PIGLIN_BARTER);
        return roll(test, "gameplay/piglin_bartering", params);
    }

    @GameTest
    public void onlyKnownLowerDropsOnPlayerKill(GameTestHelper test) {
        // 用同一批固定种子核对死者学卷和玩家击杀两个独立条件。
        var player = test.makeMockServerPlayerInLevel();
        try {
            for (var type : List.of(EntityTypes.PIGLIN, EntityTypes.ZOMBIFIED_PIGLIN)) {
                var master = born(test, type, Items.GOLDEN_SWORD, HumanoidTier.TRUE_QI);
                var novice = born(test, type, Items.GOLDEN_SWORD, HumanoidTier.WANDERING_QI);
                var knowsLower = new TechniqueScrollDrops.KnowsVolume(PREFIX + "lower");
                test.assertTrue(knowsLower.knows(master), "掉落条件未识别已学下卷 " + type);
                var reward = death(test, master, player);
                test.assertTrue(rare(reward.lower()) && reward.upper() == 0, "会下卷的死者掉卷异常 " + type + reward);
                test.assertTrue(death(test, novice, player).lower() == 0, "只会上卷的死者掉了下卷");
                test.assertTrue(death(test, master, null).lower() == 0, "环境伤害触发下卷掉落");
                confirmDeathDrop(test, master, player, reward.lowerSeed());
            }
        } finally {
            test.getLevel().getServer().getPlayerList().remove(player);
        }
        test.succeed();
    }

    private static Rolls death(GameTestHelper test, Mob mob, ServerPlayer player) {
        // 读取死亡掉落使用的同一张实体表。
        var level = test.getLevel();
        var params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, mob.position())
                .withParameter(LootContextParams.THIS_ENTITY, mob);
        if (player == null) {
            params.withParameter(LootContextParams.DAMAGE_SOURCE, level.damageSources().generic());
        } else {
            params.withParameter(LootContextParams.DAMAGE_SOURCE, level.damageSources().playerAttack(player))
                    .withParameter(LootContextParams.ATTACKING_ENTITY, player)
                    .withParameter(LootContextParams.DIRECT_ATTACKING_ENTITY, player)
                    .withParameter(LootContextParams.LAST_DAMAGE_PLAYER, player)
                    .withLuck(player.getLuck());
        }
        String table = mob.getType().getDefaultLootTable().orElseThrow().identifier().getPath();
        return roll(test, table, params.create(LootContextParamSets.ENTITY));
    }

    private static void confirmDeathDrop(GameTestHelper test, Mob master, ServerPlayer player, long seed) {
        // 用已经从正式表找到的种子走一次完整死亡流程，核实功法知识在掉落那一刻仍可读。
        var level = test.getLevel();
        var server = level.getServer();
        var source = server.createCommandSourceStack().withLevel(level);
        server.getCommands().performPrefixedCommand(source,
                "data merge entity " + master.getStringUUID() + " {DeathLootTableSeed:" + seed + "L}");
        test.assertTrue(master.getLootTableSeed() == seed, "未写入实体掉落种子");
        var area = master.getBoundingBox().inflate(1);
        var scroll = item(LOWER);
        int before = level.getEntities(EntityTypes.ITEM, area, entity -> entity.getItem().is(scroll)).size();
        master.setLastHurtByPlayer(player, AI_TIMEOUT);
        master.die(level.damageSources().playerAttack(player));
        int after = level.getEntities(EntityTypes.ITEM, area, entity -> entity.getItem().is(scroll)).size();
        test.assertTrue(after == before + 1, "玩家击杀后没有落地一份下卷 " + master.getType());
    }

    private record Rolls(int upper, int lower, int ordinary, long lowerSeed) { }

    private static Rolls roll(GameTestHelper test, String path, LootParams params) {
        // 固定种子使稀有掉落可以复验；统计每次抽取是否另有原版奖励。
        var id = Identifier.withDefaultNamespace(path);
        var key = ResourceKey.create(Registries.LOOT_TABLE, id);
        var table = test.getLevel().getServer().reloadableRegistries().getLootTable(key);
        var upper = item(UPPER);
        var lower = item(LOWER);
        int upperCount = 0;
        int lowerCount = 0;
        int ordinary = 0;
        long lowerSeed = 0;
        var seeds = new SplittableRandom(1);
        for (int roll = 0; roll < ROLLS; roll++) {
            long seed = seeds.nextLong();
            var stacks = table.getRandomItems(params, seed);
            upperCount += stacks.stream().filter(stack -> stack.is(upper)).mapToInt(ItemStack::getCount).sum();
            int lowerStacks = stacks.stream().filter(stack -> stack.is(lower)).mapToInt(ItemStack::getCount).sum();
            if (lowerStacks > 0) {
                lowerSeed = seed;
                lowerCount += lowerStacks;
            }
            if (stacks.stream().anyMatch(stack -> !stack.is(upper) && !stack.is(lower))) {
                ordinary++;
            }
        }
        return new Rolls(upperCount, lowerCount, ordinary, lowerSeed);
    }

    private static boolean rare(int drops) {
        // 同时挡住空池和必掉的配置错误。
        return drops > 0 && drops < MAX_RARE;
    }

    @GameTest
    public void creativeAndGiveExposeBothScrolls(GameTestHelper test) {
        // 填充实际创造栏，再用原版 give 验证同一物品注册名。
        var level = test.getLevel();
        var group = TechniqueScrollItems.group();
        var display = new CreativeModeTab.ItemDisplayParameters(level.enabledFeatures(),
                true /* hasPermissions */, level.registryAccess());
        group.buildContents(display);
        var player = test.makeMockServerPlayerInLevel();
        try {
            var server = level.getServer();
            for (String path : List.of(UPPER, LOWER)) {
                Item scroll = item(path);
                test.assertTrue(group.getDisplayItems().stream().anyMatch(stack -> stack.is(scroll)), "创造栏缺 " + path);
                var source = server.createCommandSourceStack().withEntity(player);
                server.getCommands().performPrefixedCommand(source, "give @s " + PREFIX + path);
                test.assertTrue(player.getInventory().contains(stack -> stack.is(scroll)), "give 没给出 " + path);
            }
        } finally {
            level.getServer().getPlayerList().remove(player);
        }
        test.succeed();
    }

    @GameTest
    public void birthRequiresAdultSwordAndRank(GameTestHelper test) {
        // 两种武器档位一致，换剑不会补发出生卷。
        for (var type : List.of(EntityTypes.PIGLIN, EntityTypes.ZOMBIFIED_PIGLIN)) {
            var novice = born(test, type, Items.GOLDEN_SWORD, HumanoidTier.WANDERING_QI);
            test.assertTrue(knows(novice, "spring_back") && !knows(novice, "coil"), "游炁出生卷错误");
            var master = born(test, type, Items.GOLDEN_SWORD, HumanoidTier.TRUE_QI);
            test.assertTrue(knows(master, "wind_around") && knows(master, "molten_flow"), "真炁出生卷错误");
            var mortal = born(test, type, Items.GOLDEN_SWORD, HumanoidTier.MORTAL);
            test.assertFalse(knows(mortal, "wind_around"), "凡人出生得剑卷");
            var baby = type.create(test.getLevel(), EntitySpawnReason.COMMAND);
            baby.setBaby(true /* baby */);
            baby.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GOLDEN_SWORD));
            add(test, baby);
            test.assertTrue(VanillaDamageFabric.inspectBody(baby).isEmpty(), "幼体被认领了身体");
            for (var weapon : List.of(Items.GOLDEN_SPEAR, Items.CROSSBOW)) {
                var other = born(test, type, weapon, HumanoidTier.TRUE_QI);
                other.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GOLDEN_SWORD));
                test.assertFalse(knows(other, "wind_around"), "原持 " + weapon + " 的生物因换剑获卷");
            }
        }
        var brute = born(test, EntityTypes.PIGLIN_BRUTE, Items.GOLDEN_AXE, HumanoidTier.TRUE_QI);
        test.assertTrue(brute.getMainHandItem().is(Items.GOLDEN_AXE) && !knows(brute, "wind_around"), "蛮兵出生换剑");
        test.assertFalse(HumanoidBrain.managed(brute), "蛮兵的 Brain 被接管");
        test.succeed();
    }

    @GameTest
    public void vanillaEquipmentGatesCorpseVolumes(GameTestHelper test) {
        // 原版装备入口须能生成两种兵器，只有确实带剑的个体接受出生卷。
        var random = RandomSource.create(1);
        var level = test.getLevel();
        var difficulty = level.getCurrentDifficultyAt(test.absolutePos(SPAWN));
        int swords = 0;
        int spears = 0;
        for (int draw = 0; draw < WEAPON_ROLLS && (swords == 0 || spears == 0); draw++) {
            var corpse = EntityTypes.ZOMBIFIED_PIGLIN.create(level, EntitySpawnReason.NATURAL);
            corpse.populateDefaultEquipmentSlots(random, difficulty);
            HumanoidSpeciesBirths.birth(corpse, HumanoidTier.TRUE_QI);
            add(test, corpse);
            var held = corpse.getMainHandItem();
            if (held.is(Items.GOLDEN_SWORD)) {
                swords++;
                test.assertTrue(knows(corpse, "coil"), "原生金剑尸修未学卷");
            } else {
                spears++;
                test.assertTrue(held.is(Items.GOLDEN_SPEAR) && !knows(corpse, "coil"), "原生金矛被换掉或得卷");
            }
            corpse.discard();
        }
        test.assertTrue(swords > 0 && spears > 0, "没有覆盖原版金剑和金矛两个分支");
        for (var weapon : List.of(Items.GOLDEN_AXE, Items.GOLDEN_SPEAR, Items.CROSSBOW)) {
            test.assertFalse(new ItemStack(weapon).is(SOFT), "软剑标签误收 " + weapon);
        }
        test.succeed();
    }

    @GameTest(maxTicks = AI_TIMEOUT)
    public void piglinUsesWindOnBrainTarget(GameTestHelper test) {
        // 让 Brain 自己从愤怒记忆选择举盾目标。
        var caster = born(test, EntityTypes.PIGLIN, Items.GOLDEN_SWORD, HumanoidTier.TRUE_QI);
        var target = shieldTarget(test, caster);
        caster.getBrain().setMemory(MemoryModuleType.ANGRY_AT, target.getUUID());
        test.succeedWhen(() -> {
            test.assertTrue(caster.getTarget() == target && !HumanoidBrain.managed(caster), "原版 Brain 未选中目标");
            assertCast(test, caster, target, "wind_around");
        });
    }

    @GameTest(maxTicks = AI_TIMEOUT)
    public void corpseUsesWindOnAngerTarget(GameTestHelper test) {
        // 僵尸猪灵保持自己的 Goal 和金剑，观察原目标上的真实施放。
        var caster = born(test, EntityTypes.ZOMBIFIED_PIGLIN, Items.GOLDEN_SWORD, HumanoidTier.TRUE_QI);
        var target = shieldTarget(test, caster);
        caster.setTarget(target);
        test.succeedWhen(() -> assertCast(test, caster, target, "wind_around"));
    }

    @GameTest(maxTicks = AI_TIMEOUT)
    public void fundedCorpseUsesItsGoalTarget(GameTestHelper test) {
        // 单独核对手经足额时的 AI 与真实兵器，用以区分出生余额和目标选择。
        var caster = born(test, EntityTypes.ZOMBIFIED_PIGLIN, Items.GOLDEN_SWORD, HumanoidTier.TRUE_QI);
        var qi = DebugQi.newBuilder()
                .setMode(DebugQiMode.FILL_LUMENS)
                .setDensity(QiDensity.baseline(QiPhase.GAS))
                .build();
        test.assertTrue(HumanoidHealthHost.debugQi(caster, qi) == FailureCode.FAILURE_CODE_UNSPECIFIED,
                "尸身手经未能装入气态真元");
        var target = shieldTarget(test, caster);
        caster.setTarget(target);
        test.succeedWhen(() -> assertCast(test, caster, target, "wind_around"));
    }

    @GameTest
    public void practitionersChooseOtherKnownForms(GameTestHelper test) {
        // 各情境使用独立身体，直接检查正式择式入口提交的招式和目标。
        for (String form : List.of("quiver", "molten_flow", "coil", "spring_back")) {
            var caster = born(test, EntityTypes.PIGLIN, Items.GOLDEN_SWORD, HumanoidTier.TRUE_QI);
            var target = test.spawn(EntityTypes.COW, SPAWN);
            target.setNoAi(true /* noAi */);
            double distance = MELEE_RANGE;
            if (form.equals("molten_flow")) {
                distance = FLOW_DISTANCE;
            }
            target.setPos(caster.position().add(0, 0, distance));
            caster.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, target);
            if (form.equals("coil")) {
                target.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
                var ally = test.spawn(EntityTypes.ZOMBIE, SPAWN);
                ally.setTarget(target);
            }
            if (form.equals("spring_back")) {
                var service = TechniqueFabric.service();
                var body = service.statusHost(caster.getUUID()).orElseThrow().inspect().body();
                var fact = new BlockedAttackObservation(caster.getUUID(), caster.getStringUUID(),
                        BlockedAttackSource.SHIELD, body.settledAt().elapsedMillis());
                test.assertTrue(service.recordBlockedAttack(fact), "未登记被挡事实");
            }
            test.assertTrue(HumanoidSwordForms.tryCast(caster), "没有成功起式 " + form);
            assertCast(test, caster, target, form);
        }
        test.succeed();
    }

    static Mob shieldTarget(GameTestHelper test, Mob caster) {
        // 对手原地持盾，双方保持在目录的近战距离内。
        caster.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0);
        var target = test.spawn(EntityTypes.VINDICATOR, SPAWN);
        target.setNoAi(true /* noAi */);
        target.setNoGravity(true /* noGravity */);
        target.setPos(caster.position().add(0, 0, MELEE_RANGE));
        target.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
        target.startUsingItem(InteractionHand.OFF_HAND);
        return target;
    }

    private static void assertCast(GameTestHelper test, Mob caster, Mob target, String form) {
        // 只有功法服务确认目标身份和起手才能通过。
        var observation = TechniqueFabric.service().observe(caster.getUUID());
        test.assertTrue(observation.isPresent(), "宿主尚未就绪");
        var cast = observation.orElseThrow().cast();
        test.assertTrue(cast.isPresent(), "持金剑修士尚未起式");
        var request = cast.orElseThrow().active().getRequest();
        test.assertTrue(request.getTechnique().getId().equals(PREFIX + form)
                && request.getTargetId().equals(target.getStringUUID()), "预期 " + form + " 实际 " + request);
    }

    @GameTest(maxTicks = AI_TIMEOUT)
    public void zombificationCarriesSwordAndRank(GameTestHelper test) {
        // 从原版僵化计时的末刻开始，核对新尸身、尸丹和重新授予的两卷。
        var piglin = born(test, EntityTypes.PIGLIN, Items.GOLDEN_SWORD, HumanoidTier.TRUE_QI);
        piglin.setTimeInOverworld(Piglin.CONVERSION_TIME);
        var marker = piglin.getStringUUID();
        piglin.addTag(marker);
        test.succeedWhen(() -> {
            var corpses = test.getLevel().getEntities(EntityTypes.ZOMBIFIED_PIGLIN,
                    piglin.getBoundingBox().inflate(MELEE_RANGE), mob -> mob.entityTags().contains(marker));
            test.assertTrue(piglin.isRemoved() && corpses.size() == 1, "尚未完成原版僵化");
            var corpse = corpses.getFirst();
            var body = VanillaDamageFabric.inspectBody(corpse).orElseThrow();
            test.assertTrue(body.getTemplate().getId().equals("meridian_undead:corpse")
                    && body.getOrganStatesList().stream().anyMatch(organ -> organ.getId().equals("corpse_pearl")),
                    "僵化未生成带尸丹的尸身");
            test.assertTrue(HumanoidTier.of(body) == HumanoidTier.TRUE_QI && knows(corpse, "molten_flow"),
                    "僵化丢失档位或下卷");
            test.assertTrue(corpse.getMainHandItem().is(Items.GOLDEN_SWORD) && corpse.fireImmune(), "僵化丢失装备或火免");
        });
    }

    static <T extends Mob> T born(GameTestHelper test, EntityType<T> type, Item weapon, HumanoidTier tier) {
        // 出生时已经确定原版武器，之后再加入世界供真实宿主读取。
        var mob = type.create(test.getLevel(), EntitySpawnReason.COMMAND);
        mob.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(weapon));
        mob.setNoGravity(true /* noGravity */);
        HumanoidSpeciesBirths.birth(mob, tier);
        add(test, mob);
        return mob;
    }

    private static void add(GameTestHelper test, Mob mob) {
        // 使用测试结构自己的绝对坐标。
        mob.setPos(Vec3.atCenterOf(test.absolutePos(SPAWN)));
        test.getLevel().addFreshEntity(mob);
    }

    private static boolean knows(Mob mob, String form) {
        // 查询生产宿主的已学招式。
        return TechniqueFabric.service().known(mob.getUUID()).stream()
                .anyMatch(entry -> entry.technique().getId().equals(PREFIX + form));
    }

    private static Item item(String path) {
        // 缺少注册直接终止用例。
        return BuiltInRegistries.ITEM.getOptional(Identifier.parse(PREFIX + path)).orElseThrow();
    }
}
