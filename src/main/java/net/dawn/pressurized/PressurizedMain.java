package net.dawn.pressurized;

//TODO: SERVER-HOSTING EDGECASE: someetimes the player doesnt take Pressure damage nor have any Pressure camera visuals and can be fixed by rejoining.

//code may not be perfect, as i am not too familiar with minecraft modding... but that does not bother me at the moment.

//NOTICE1: do NOT use == operator on BlockPos with another BlockPos, USE .equals INSTEAD.

//NOTICE2: Pressure damage and Crush damage are not the same source.
//Pressure: occurs when ascending/descending too fast.
//Crush: occurs when the Entity is at a great depth.

//NOTICE2: Ship blocks have 2 different positions.

//Position 1: Shipyard position.
//this position is static, which is important as it can be used as the key to the ship block.
//do be aware that Shipyard position is far away from its World position, so it should only be used as a key to the block,

//Position 2: World position.
//this position is dynamic, which is important to be aware of since it constantly changes and cannot act as a key to a ship block.

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.dawn.pressurized.Client.ClientConfigs;
import net.dawn.pressurized.Client.PressurizedClient;
import net.dawn.pressurized.Network.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Math;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import static net.dawn.pressurized.BlocksResistanceData.*;
import static net.dawn.pressurized.VSCompat.*;

@Mod(PressurizedMain.MODID)
public class PressurizedMain {
    public static final String MODID = "pressurized";

    private PressurizedClient HudOverlay;

    static HashMap<String, Integer> BlocksPressureResistance = new HashMap<>();
    public static final ConcurrentHashMap<Integer, Map.Entry<BlockPos, Integer>> CrushedBlocks = new ConcurrentHashMap<>();
    public static final HashMap<Entity, Integer> EntitiesDepth = new HashMap<>();

    static final Map<BlockPos, Boolean> PendingCrushActions = new ConcurrentHashMap<>();
    static final ArrayList<Integer> RemovalCollection = new ArrayList<>();

    static int ServerTicks = 0;

    public static final boolean DevMode = false;

    public PressurizedMain(FMLJavaModLoadingContext context) {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        modEventBus.addListener(this::CommonSetup);

        modEventBus.addListener(this::RegisterGui);
        modEventBus.addListener(this::clientSetup);

        MinecraftForge.EVENT_BUS.register(this);

        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, ServerConfigs.SPEC, "pressurized-Server.toml");
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, ClientConfigs.SPEC, "pressurized-Client.toml");

        System.setProperty("forge.enableStencil", "true");

        Networking.register();
        ModSounds.Register(modEventBus);

        BlocksPressureResistance.put("bedrock", 100000);

        ForgeRegistries.BLOCKS.getEntries().forEach(entry -> {
            String Name = Objects.requireNonNull(ForgeRegistries.BLOCKS.getKey(entry.getValue())).getPath();
            boolean Add = true;
            int Resistance = 10;

            for (Map.Entry<String, Integer> stringIntegerHashMap : BlocksPressureResistance.entrySet()) {

                for (String BlockName : StoneBlocks) {
                    if (BlockName.equals(Name)) {
                        Resistance = 40;
                        break;
                    }
                }
                for (String BlockName : WoodBlocks) {
                    if (BlockName.equals(Name)) {
                        Resistance = 10;
                        break;
                    }
                }
                for (String BlockName : MetalBlocks) {
                    if (BlockName.equals(Name)) {
                        Resistance = 80;
                        break;
                    }
                }

                if (stringIntegerHashMap.getKey().equals(Name)) {
                    Add = false;
                    break;
                }
            }
            if (Add) {
                BlocksPressureResistance.put(Name, Resistance);
            }
        });
    }

    private void clientSetup(final FMLClientSetupEvent event)
    {
        HudOverlay = new PressurizedClient();
    }

    private void CommonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            if (ModList.get().isLoaded("valkyrienskies")) {
                VSCompat.CommonSetup(event);
            }
        });
    }

    public void RegisterGui(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("idk", PressurizedClient.PressurizedHUD);
        HudOverlay = new PressurizedClient();
        HudOverlay.initOverlays(event);
    }

    public static void BlockScan(BlockPos blockPos, ServerLevel level) { // i geneuinely hate this code and needs a lot of cleaning
        HashMap<Integer, BlockPos> DistancePos = BlockposDepth(blockPos, level);
        int BlockDepth = 0;

        for (Map.Entry<Integer, BlockPos> entry : DistancePos.entrySet()) {
            if (!(entry.getKey() == null)) {
                BlockDepth = entry.getKey();
            }
            if (!(entry.getValue() == null)) {
                blockPos = entry.getValue();
            }
        }

        String name = Objects.requireNonNull(ForgeRegistries.BLOCKS.getKey(level.getBlockState(blockPos).getBlock())).getPath();
        BlockState blockState = level.getBlockState(blockPos);

        if (blockState.isSolid() & BlocksPressureResistance.containsKey(name)) {
            int Resistance = BlocksPressureResistance.get(name);

            BlockPos LowerBlockPos = blockPos.below();
            String LowerBlockName = Objects.requireNonNull(Objects.requireNonNull(ForgeRegistries.BLOCKS.getKey(level.getBlockState(LowerBlockPos).getBlock())).getPath());
            while (!LowerBlockName.equals("void_air") & !LowerBlockName.equals("water") & !LowerBlockName.equals("air") & BlocksPressureResistance.containsKey(LowerBlockName)) {
                Resistance += (BlocksPressureResistance.get(LowerBlockName));
                LowerBlockPos = LowerBlockPos.below();
                LowerBlockName = Objects.requireNonNull(Objects.requireNonNull(ForgeRegistries.BLOCKS.getKey(level.getBlockState(LowerBlockPos).getBlock())).getPath());
            }

            if (BlockDepth * ServerConfigs.CrushDepthMultiplier.get() >= Resistance) {

                if (CrushedBlocks.isEmpty() & CrushedBlocks.size() < ServerConfigs.MaxBlocksDestructionCapacity.get()) {
                    Map.Entry<BlockPos, Integer> entry = Map.entry(blockPos, 0);
                    CrushedBlocks.put(CrushedBlocks.size()+1, entry);
                    Networking.CHANNEL3.send(PacketDistributor.ALL.noArg(), new UpdateCBArray(CrushedBlocks.size(), blockPos));
                }

                boolean found = false;
                for (Map.Entry<Integer, Map.Entry<BlockPos, Integer>> BlockMap : CrushedBlocks.entrySet()) {
                    if (BlockMap.getValue().getKey().equals(blockPos)) {
                        found = true;
                        break;
                    }
                }

                if (!found & CrushedBlocks.size() < ServerConfigs.MaxBlocksDestructionCapacity.get()) {
                    Map.Entry<BlockPos, Integer> entry = Map.entry(blockPos, 0);

                    CrushedBlocks.put(CrushedBlocks.size()+1, entry);
                    Networking.CHANNEL3.send(PacketDistributor.ALL.noArg(), new UpdateCBArray(CrushedBlocks.size(), blockPos));
                }

                if (ServerTicks >= ServerConfigs.BlockScanRate.get()) {
                    for (Map.Entry<Integer, Map.Entry<BlockPos, Integer>> BlockMap : CrushedBlocks.entrySet()) {
                        Map.Entry<BlockPos, Integer> entry = BlockMap.getValue();
                        BlockPos key = entry.getKey();
                        if (PendingCrushActions.containsKey(key)) {
                            continue;
                        }

                        if (level.getBlockState(key.above()).isSolid()) {
                            RemovalCollection.add(BlockMap.getKey());
                            continue;
                        }
                        if (PendingCrushActions.putIfAbsent(key, Boolean.TRUE) != null) {
                            continue;
                        }

                        final int blockId = BlockMap.getKey();
                        final BlockPos pendingKey = key;
                        long Delay = ThreadLocalRandom.current().nextLong(0, 5000);

                        Thread t = new Thread(() -> {
                            try {
                                Thread.sleep(Delay);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                level.getServer().execute(() -> PendingCrushActions.remove(pendingKey));
                                return;
                            }

                            level.getServer().execute(() -> {
                                try {
                                    Map.Entry<BlockPos, Integer> currentEntry = CrushedBlocks.get(blockId);
                                    if (currentEntry == null || !currentEntry.getKey().equals(pendingKey)) {
                                        return;
                                    }

                                    if (level.getBlockState(pendingKey.above()).isSolid()) {
                                        RemovalCollection.add(blockId);
                                        return;
                                    }

                                    if (currentEntry.getValue() > 7) {
                                        RemovalCollection.add(blockId);
                                        level.destroyBlock(pendingKey, true);
                                        level.addDestroyBlockEffect(pendingKey, level.getBlockState(pendingKey));
                                    } else {
                                        Networking.CHANNEL4.send(
                                                PacketDistributor.ALL.noArg(),
                                                new UpdateCBTexture(currentEntry.getKey(), currentEntry.getValue()+1)
                                        );
                                        CrushedBlocks.put(blockId, Map.entry(currentEntry.getKey(), currentEntry.getValue()+1));
                                    }
                                } finally {
                                    PendingCrushActions.remove(pendingKey);
                                }
                            });
                        }, "Pressurized-Crush-" + pendingKey.asLong());
                        t.setDaemon(true);
                        t.start();
                    }
                }
                ServerTicks = 0;
            }
        } else {
            for (Map.Entry<Integer, Map.Entry<BlockPos, Integer>> BlockMap : CrushedBlocks.entrySet()) {
                Map.Entry<BlockPos, Integer> entry = BlockMap.getValue();
                if (entry.getKey().equals(blockPos)) {
                    RemovalCollection.add(BlockMap.getKey());
                }
            }
        }
        for (Integer Num : RemovalCollection) {
            CrushedBlocks.remove(Num);
        }
        RemovalCollection.clear();
    }

    public static HashMap<Integer, BlockPos> BlockposDepth(BlockPos BlockPos, ServerLevel SLevel) {
        BlockPos currentPos = BlockPos.above();
        BlockPos highestWaterBlock = BlockPos;

        BlockPos NewBlockpos = null;
        int Distance;
        HashMap<Integer, net.minecraft.core.BlockPos> DistancePos = new HashMap<>();

        while (currentPos.getY() < SLevel.getHeight()) {
            if (SLevel.getBlockState(currentPos).liquid()) {
                highestWaterBlock = currentPos;
            } else {
                break;
            }
            currentPos = currentPos.above();
        }

        Distance = (int) highestWaterBlock.getCenter().distanceTo(BlockPos.getCenter());

        BlockHitResult context = SLevel.clip(new ClipContext(
                highestWaterBlock.getCenter(),
                BlockPos.getCenter(),
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                null
        ));

        if (context.getType() == HitResult.Type.BLOCK) {
            Distance = (int) highestWaterBlock.getCenter().distanceTo(context.getBlockPos().getCenter());
            NewBlockpos = context.getBlockPos();

            if (ModList.get().isLoaded("valkyrienskies")) {
                BlockPos meow = VSCompat.valkShipToWorld(SLevel, context.getBlockPos());

                if (meow != null) {
                    Distance = (int) highestWaterBlock.getCenter().distanceTo(meow.getCenter());
                }
            }
        }

        DistancePos.put(Distance, NewBlockpos);

        return DistancePos;
    }
    public static int EntityDepth(BlockPos blockPos, ClientLevel level) {
        if (ModList.get().isLoaded("valkyrienskies")) {
            BlockPos meow = VSCompat.valkShipToWorld(level, blockPos);

            if (meow != null) {
                blockPos = meow;
            }
        }

        BlockPos currentPos = blockPos.above();
        BlockPos highestWaterBlock = blockPos;

        while (currentPos.getY() < level.getHeight()) {
            if (level.getBlockState(currentPos).getFluidState().is(Fluids.WATER) || level.getBlockState(currentPos).getBlock() == Blocks.WATER) {
                highestWaterBlock = currentPos;
            } else {
                break;
            }
            currentPos = currentPos.above();
        }
        return highestWaterBlock.getY();
    }

    @SubscribeEvent
    public void PRLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        EntitiesDepth.put(event.getEntity(), 0);


        if (ModList.get().isLoaded("valkyrienskies")) {
            int i = 0;
            while (i < AirPockets.size()) {
                AABB Airpocket = AirPockets.get(i);

                BlockPos Min = new BlockPos((int) Airpocket.minX, (int) Airpocket.minY, (int) Airpocket.minZ);
                BlockPos Max = new BlockPos((int) Airpocket.maxX, (int) Airpocket.maxY, (int) Airpocket.maxZ);

                Networking.CHANNEL6.send(PacketDistributor.ALL.noArg(), new UpdateAP(i, Min, Max));
                i++;
            }
        }
    }


    @SubscribeEvent
    public void PRLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        EntitiesDepth.remove(event.getEntity());

        //if (Minecraft.getInstance().isSingleplayer()) {
        if (ModList.get().isLoaded("valkyrienskies")) {
            AirPockets.clear(); //TODO: remove this later
            VSCompat.Debounce = false; //TODO: remove this later
        }
        //}
    }


        boolean ProcessedCrushActions = false;
    int Delay = 0;

    @SubscribeEvent
    public void OnSTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            if (ModList.get().isLoaded("valkyrienskies")) {
                VSCompat.OnSTick(event);
            }
        }

        if (!event.phase.equals(TickEvent.Phase.END)) {return;}
        Delay++;

        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            if (ModList.get().isLoaded("valkyrienskies")) {

                if (!VSCompat.Debounce && Delay >= 20) {
                    VSCompat.RegisterShipAirpockets(player.serverLevel());
                }
                if (VSCompat.UpdateAirpockets) {
                    VSCompat.RegisterShipAirpockets(player.serverLevel());
                    VSCompat.UpdateAirpockets = false;
                }
            }

            if (ServerTicks < ServerConfigs.BlockScanRate.get()) {ServerTicks++;return;}

            //NOTICE: the reason why were getting the Depth on the server instead of client is because valk skies
            //VSGameUtilsKt.getShipObjectManagingPos needs ServerLevel
            //actually that may be false

            //            for (Map.Entry<Entity, Integer> Entry : EntitiesDepth.entrySet()) {
            //                if (player.getId() == Entry.getKey().getId()) {
            //                    int Depth = EntityDepth(player.getOnPos(), player.serverLevel());
            //                    Entry.setValue((int) (Entry.getKey().getY() - Depth));
            //                    Networking.CHANNEL5.send(PacketDistributor.PLAYER.with(() -> player), new SendPlayerDepth(Entry.getValue()));
            //                }
            //            }

            int radius = ServerConfigs.BlockScanRadius.get();
            BlockPos center = player.getOnPos();
            for (int x = -radius; x <= radius; x++) {
                for (int y = -radius; y <= radius; y++) {
                    for (int z = -radius; z <= radius; z++) {
                        BlockPos blockPos = center.offset(x, y, z);
                        if (blockPos.distSqr(center) <= radius * radius) {
                            if (player.serverLevel().getFluidState(blockPos).isEmpty()) {
                                BlockScan(blockPos, player.serverLevel()); // this is the cool part
                            }
                        }
                    }
                }
            }
        }
        ServerTicks = 0;
    }

    @SubscribeEvent
    public void BlockDestroyed(BlockEvent.BreakEvent event) {
        if (!event.getLevel().isClientSide()) {
            if (ModList.get().isLoaded("valkyrienskies")) {

                ArrayList<BlockPos> TempExcludedBlocks = new ArrayList<>();

                TempExcludedBlocks.add(event.getPos());
                TempExcludedBlocks.add(event.getPos().north());
                TempExcludedBlocks.add(event.getPos().east());
                TempExcludedBlocks.add(event.getPos().south());
                TempExcludedBlocks.add(event.getPos().west());

                ArrayList<AABB> ExcludedAirpockets = new ArrayList<>();
                for (int b = AirPockets.size() - 1; b >= 0; b--) {
                    AABB Airpocket = AirPockets.get(b);
                    boolean Exclude = false;

                    if (ExcludedAirpockets.contains(Airpocket)) {continue;}

                    for (int i = TempExcludedBlocks.size() - 1; i >= 0; i--) {
                        if (Airpocket.contains(TempExcludedBlocks.get(i).getCenter())) {
                            Exclude = true;
                        }
                    }

                    if (Exclude) {
                        ExcludedAirpockets.add(Airpocket);

                        for (double x = Airpocket.minX; x < Airpocket.maxX; x++) {
                            for (double y = Airpocket.minY; y < Airpocket.maxY; y++) {
                                for (double z = Airpocket.minZ; z < Airpocket.maxZ; z++) {
                                    BlockPos block = new BlockPos((int) x, (int) y, (int) z);
                                    TempExcludedBlocks.add(block);
                                    b = 0;
                                }
                            }
                        }
                    }
                }
                for (AABB Airpocket : ExcludedAirpockets) {
                    AirPocketsVisuals.remove(Airpocket);
                    AirPockets.remove(Airpocket);
                }

                TempExcludedBlocks.clear();

                VSCompat.UpdateAirpockets = true;
            }

            for (Map.Entry<Integer, Map.Entry<BlockPos, Integer>> BlockMap : CrushedBlocks.entrySet()) {
                Map.Entry<BlockPos, Integer> entry = BlockMap.getValue();
                if (entry.getKey().equals(event.getPos())) {
                    RemovalCollection.add(BlockMap.getKey());
                }
            }
        }
    }

    @SubscribeEvent
    public void BlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (!event.getLevel().isClientSide() && event.getPhase() == EventPriority.NORMAL) {
            if (ModList.get().isLoaded("valkyrienskies")) {

                ArrayList<BlockPos> TempExcludedBlocks = new ArrayList<>();

                TempExcludedBlocks.add(event.getPos());
                TempExcludedBlocks.add(event.getPos().north());
                TempExcludedBlocks.add(event.getPos().east());
                TempExcludedBlocks.add(event.getPos().south());
                TempExcludedBlocks.add(event.getPos().west());

                ArrayList<AABB> ExcludedAirpockets = new ArrayList<>();
                for (int b = AirPockets.size() - 1; b >= 0; b--) {
                    AABB Airpocket = AirPockets.get(b);
                    if (ExcludedAirpockets.contains(Airpocket)) {continue;}

                    boolean Exclude = false;

                    for (int i = TempExcludedBlocks.size() - 1; i >= 0; i--) {
                        if (Airpocket.contains(TempExcludedBlocks.get(i).getCenter())) {
                            Exclude = true;
                        }
                    }

                    if (Exclude) {
                        ExcludedAirpockets.add(Airpocket);

                        for (double x = Airpocket.minX; x < Airpocket.maxX; x++) {
                            for (double y = Airpocket.minY; y < Airpocket.maxY; y++) {
                                for (double z = Airpocket.minZ; z < Airpocket.maxZ; z++) {
                                    BlockPos block = new BlockPos((int) x, (int) y, (int) z);
                                    TempExcludedBlocks.add(block);

                                    TempExcludedBlocks.add(block.north());
                                    TempExcludedBlocks.add(block.east());
                                    TempExcludedBlocks.add(block.south());
                                    TempExcludedBlocks.add(block.west());
                                    b = 0;
                                }
                            }
                        }
                    }
                }
                for (AABB Airpocket : ExcludedAirpockets) {
                    AirPocketsVisuals.remove(Airpocket);
                    AirPockets.remove(Airpocket);
                }

                TempExcludedBlocks.clear();
                VSCompat.RegisterShipAirpockets((ServerLevel) event.getLevel());
            }
        }
    }

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static class ClientModEvents {

        //NOTICE: It seems like all other event listeners dont work without subscribing to this one below.
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event)
        {}

        //i should probably keep it client-sided for break texture rendering
        //because last time i tried handling it on the server using level.destroyBlockProgress, the client rendered it a bit inconsistently...

        @SubscribeEvent
        public static void OnRLSEvent(RenderLevelStageEvent event) {
            if (Minecraft.getInstance().player == null || event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
                return;
            }

            if (DevMode) { //Airpockets highlight debugger start
                Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();

                PoseStack poseStack = event.getPoseStack();
                poseStack.pushPose();
                poseStack.translate(-camera.x, -camera.y, -camera.z);

                MultiBufferSource.BufferSource bufferSource = MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());

                VertexConsumer consumer = bufferSource.getBuffer(RenderType.lines());

                RenderSystem.setShader(GameRenderer::getPositionColorShader);

                if (ModList.get().isLoaded("valkyrienskies")) {
                    try {
                        for (int i = AirPockets.size() - 1; i >= 0; i--) {
                            AABB Airpocket = AirPockets.get(i);
                            if (Airpocket != null && AirPocketsVisuals.get(Airpocket) != null) {
                                Debugger.renderLineBox(
                                        poseStack,
                                        consumer,
                                        VSCompat.AirPocketsVisuals.get(Airpocket)
                                );

                            }
                        }

                    } catch(IndexOutOfBoundsException exception) {
                        bufferSource.endBatch(RenderType.lines());
                    }
                }
                bufferSource.endBatch(RenderType.lines());

                poseStack.popPose();
            }//Airpockets highlight debugger end

            for (Map.Entry<Integer, Map.Entry<BlockPos, Integer>> BlockMap : PressurizedMain.CrushedBlocks.entrySet()) {
                event.getLevelRenderer().destroyBlockProgress(
                        BlockMap.getKey(),
                        BlockMap.getValue().getKey(),
                        BlockMap.getValue().getValue()
                );
                //NOTICE: First parameter of destroyBlockProgress is for the id of the entity, this may result in issues.
            }
        }

        //am not sure if LoggedIn is actually needed.
        static Boolean LoggedIn = false;

        @SubscribeEvent
        public static void PRLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
            LoggedIn = true;
        }


        @SubscribeEvent
        public static void PRLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
            LoggedIn = false;
        }

        //@SubscribeEvent
        //public static void GotOn(EntityMountEvent event) {
        //    PressurizedClient.Mounted = event.isMounting();
        //    if (event.isMounting()) {
        //PressurizedClient.PressureImmunity = true;
        //PressurizedClient.CrushImmunity = true;
        //    } else if (event.isDismounting()) {
        //PressurizedClient.PressureImmunity = false;
        //PressurizedClient.CrushImmunity = true;
        //    }
        //}

        @SubscribeEvent
        public static void OnPTick(TickEvent.ClientTickEvent event) {
            PressurizedClient.Player = Minecraft.getInstance().player;
            if (PressurizedClient.Player == null) {return;}
            PressurizedClient.Depth = PressurizedClient.Player.getY() - EntityDepth(PressurizedClient.Player.getOnPos(), Minecraft.getInstance().level);
            //NOTE: this entire if statement is for Crushed Blocks damage sfx, buh

            if (!PressurizedClient.HullDamageThread) {
                PressurizedClient.HullDamageThread = true;
                Thread hullDamageThread = new Thread(() -> {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        Minecraft.getInstance().execute(() -> PressurizedClient.HullDamageThread = false);
                        return;
                    }

                    Minecraft.getInstance().execute(() -> {
                        try {
                            if (Minecraft.getInstance().player == null || !Minecraft.getInstance().player.isUnderWater() || CrushedBlocks.isEmpty()) {
                                return;
                            }

                            PressurizedClient.Player = Minecraft.getInstance().player;

                            int X = 0;
                            int Y = 0;
                            int Z = 0;

                            for (Map.Entry<Integer, Map.Entry<BlockPos, Integer>> BlockMap : CrushedBlocks.entrySet()) {
                                BlockPos key = BlockMap.getValue().getKey();

                                X += key.getX();
                                Y += key.getY();
                                Z += key.getZ();
                            }

                            X /= CrushedBlocks.size();
                            Y /= CrushedBlocks.size();
                            Z /= CrushedBlocks.size();

                            BlockPos blockPos = new BlockPos(X, Y, Z);
                            BlockState Blockstate = Minecraft.getInstance().player.level().getBlockState(blockPos);

                            String Name = Objects.requireNonNull(ForgeRegistries.BLOCKS.getKey(Blockstate.getBlock())).getPath();

                            for (String BlockName : MetalBlocks) {
                                if (BlockName.equals(Name)) {
                                    //    event.player.playSound(ModSounds.HULLDAMAGE.get(), (float) (1f / Distance), 1f);
                                }
                            }

                            for (String BlockName : StoneBlocks) {
                                if (BlockName.equals(Name)) {
                                    //    event.player.playSound(ModSounds.ENVIRONMENTDAMAGE.get(), (float) (1f / Distance), 1f);
                                }
                            }

                            // event.player.level().playSound(null, X, Y, Z,
                            //         ModSounds.HULLDAMAGE.get(), SoundSource.BLOCKS,(float) (1f/Distance), 1f);
                        } finally {
                            PressurizedClient.HullDamageThread = false;
                        }
                    });
                }, "Pressurized-HullDamage");
                hullDamageThread.setDaemon(true);
                hullDamageThread.start();
            }

            if (PressurizedClient.PressureImmunity) {
                PressurizedClient.BodyPressure = PressurizedClient.Depth;
            } else {
                if (PressurizedClient.BodyPressure > PressurizedClient.Depth) {
                    PressurizedClient.BodyPressure -= .075;
                } else if (PressurizedClient.BodyPressure < PressurizedClient.Depth) {
                    PressurizedClient.BodyPressure += .075;
                } else if (PressurizedClient.BodyPressure - PressurizedClient.Depth >= .025) {
                    PressurizedClient.BodyPressure = PressurizedClient.Depth;
                }
            }

            if (PressurizedClient.CrushImmunity || PressurizedClient.Depth > PressurizedClient.CrushDepth) {
                if (PressurizedClient.PressureBuildup > 0) {
                    PressurizedClient.PressureBuildup -= .1;
                    if (PressurizedClient.PressureBuildup < 0) {
                        PressurizedClient.PressureBuildup = 0;
                    }
                }
            }

            if (PressurizedClient.Player.isUnderWater() & !PressurizedClient.Player.isCreative()) {
                String Helmet = PressurizedClient.Player.getItemBySlot(EquipmentSlot.HEAD).getItem().toString();
                String ChestPlate = PressurizedClient.Player.getItemBySlot(EquipmentSlot.CHEST).getItem().toString();
                String Leggings = PressurizedClient.Player.getItemBySlot(EquipmentSlot.LEGS).getHoverName().getString();
                String Boots = PressurizedClient.Player.getItemBySlot(EquipmentSlot.FEET).getHoverName().getString();

                if (!ServerConfigs.HelmetRequired.get()) {
                    PressurizedClient.ValidHelmet = true;
                } else {
                    for (String Gear : ServerConfigs.ValidHelmets.get()) {
                        PressurizedClient.ValidHelmet = (Helmet.equals(Gear));
                    }
                }

                if (!ServerConfigs.ChestPlateRequired.get()) {
                    PressurizedClient.ValidChestPlate = true;
                } else  {
                    for (String Gear : ServerConfigs.ValidChestPlates.get()) {
                        PressurizedClient.ValidChestPlate = (ChestPlate.equals(Gear));
                    }
                }

                if (!ServerConfigs.LeggingsRequired.get()) {
                    PressurizedClient.ValidLeggings = true;
                } else {
                    for (String Gear : ServerConfigs.ValidLeggings.get()) {
                        PressurizedClient.ValidLeggings = (Leggings.equals(Gear));
                    }
                }

                if (!ServerConfigs.BootsRequired.get()) {
                    PressurizedClient.ValidBoots = true;
                } else {
                    for (String Gear : ServerConfigs.ValidBoots.get()) {
                        PressurizedClient.ValidBoots = (Boots.equals(Gear));
                    }
                }

                if (PressurizedClient.ValidHelmet & PressurizedClient.ValidChestPlate & PressurizedClient.ValidLeggings & PressurizedClient.ValidBoots) {
                    PressurizedClient.PressureImmunity = true;
                    PressurizedClient.CrushDepth = -400 * ServerConfigs.CrushDepthMultiplier.get();
                } else {
                    PressurizedClient.PressureImmunity = false;
                    PressurizedClient.CrushDepth = -100 * ServerConfigs.CrushDepthMultiplier.get();
                }

                if (PressurizedClient.Player.getVehicle() != null) {
                    PressurizedClient.PressureImmunity = true;
                    PressurizedClient.CrushImmunity = true;

                    if (PressurizedClient.Depth <= -120 * ServerConfigs.CrushDepthMultiplier.get()) {
                        PressurizedClient.MountPressureBuildup += 0.025;
                        if (PressurizedClient.MountPressureBuildup >= 1 & PressurizedClient.Player.isAlive()) {
                            Networking.CHANNEL7.sendToServer(new BaroDamageBoatPacket(3));
                            //PressurizedClient.Player.getVehicle().kill();//(PressurizedClient.Player.level().damageSources().drown(), 15);
                            PressurizedClient.MountPressureBuildup = 0;
                        }
                    }
                } else {
                    PressurizedClient.CrushImmunity = false;
                    if (PressurizedClient.MountPressureBuildup > 0) {
                        PressurizedClient.MountPressureBuildup -= 0.025;
                    }
                }

                if (!PressurizedClient.CrushImmunity & PressurizedClient.Depth <= PressurizedClient.CrushDepth || !PressurizedClient.PressureImmunity && (PressurizedClient.Depth - PressurizedClient.BodyPressure <= -5 & ServerConfigs.PressureDamage.get() || PressurizedClient.Depth - PressurizedClient.BodyPressure >= 5 & ServerConfigs.ResurfaceDamage.get())) {
                    PressurizedClient.OverPressured = true;
                    if (PressurizedClient.Player.hurtTime <= 0) {
                        if (PressurizedClient.Depth - PressurizedClient.BodyPressure < 0 & PressurizedClient.Player.getDeltaMovement().y < -.15) {// damage when descending too fast
                            Minecraft.getInstance().getSoundManager().stop(ModSounds.BAROTRAUMA.getId(), SoundSource.AMBIENT);
                            PressurizedClient.Player.level().playLocalSound(PressurizedClient.Player.getX(), PressurizedClient.Player.getY(), PressurizedClient.Player.getZ(), ModSounds.BAROTRAUMA.get(), SoundSource.AMBIENT, (float) (1f * (Math.abs(PressurizedClient.Depth - PressurizedClient.BodyPressure))), 1f, false);
                            if (PressurizedClient.Player.isAlive()) {
                                //if (PressurizedClient.Player.getVehicle() != null) {
                                //    PressurizedClient.Player.getVehicle().hurt(null, 2);
                                //} else {
                                Networking.CHANNEL1.sendToServer(new BaroDamagePlayerPacket(2));
                                // }
                            }
                        } else if (PressurizedClient.Depth - PressurizedClient.BodyPressure > 0  & PressurizedClient.Player.getDeltaMovement().y > .15) {// damage when ascending too fast
                            Minecraft.getInstance().getSoundManager().stop(ModSounds.BAROTRAUMA.getId(), SoundSource.AMBIENT);
                            if (PressurizedClient.Player.isAlive()) {
                                // if (PressurizedClient.Player.getVehicle() != null) {
                                //     PressurizedClient.Player.getVehicle().hurt(null, 2);
                                // } else {
                                Networking.CHANNEL1.sendToServer(new BaroDamagePlayerPacket(2));
                                PressurizedClient.Player.level().playLocalSound(
                                        PressurizedClient.Player.getX(),
                                        PressurizedClient.Player.getY(),
                                        PressurizedClient.Player.getZ(),
                                        ModSounds.BAROTRAUMA.get(),
                                        SoundSource.AMBIENT,
                                        (float) (1f * (Math.abs(PressurizedClient.Depth - PressurizedClient.BodyPressure))),
                                        1f,
                                        false
                                );
                                // }
                                //Networking.CHANNEL1.sendToServer(new BaroDamagePlayerPacket(2));
                            }
                        }
                    }

                    if (!PressurizedClient.CrushImmunity & PressurizedClient.Depth <= PressurizedClient.CrushDepth) {
                        PressurizedClient.PressureBuildup += 0.025;
                    }
                } else {
                    PressurizedClient.OverPressured = false;
                }
                if (PressurizedClient.PressureBuildup >= 5 & PressurizedClient.Player.isAlive()) {
                    //if (PressurizedClient.Player.getVehicle() != null) {
                    //    PressurizedClient.Player.getVehicle().hurt(null, 2);
                    //   PressurizedClient.PressureBui
                    //} else {
                    Networking.CHANNEL2.sendToServer(new CrushDamagePlayerPacket(100));
                    // }
                }
            } else {
                PressurizedClient.OverPressured = false;
            }
        }

        @SubscribeEvent
        public static void OnCTick(TickEvent.RenderTickEvent event) {
            if (event.phase == TickEvent.Phase.START & PressurizedClient.Player != null) {
                if (!LoggedIn || PressurizedClient.Player.isCreative()) {return;}

                if (PressurizedClient.OverPressured & !Minecraft.getInstance().isPaused() & Minecraft.getInstance().cameraEntity != null) {
                    if (PressurizedClient.CamShake >= 1) {
                        PressurizedClient.CamShake = (int) (-1 * (Math.abs(PressurizedClient.Depth - PressurizedClient.BodyPressure)));
                    } else {
                        PressurizedClient.CamShake = (int) (1 * (Math.abs(PressurizedClient.Depth - PressurizedClient.BodyPressure)));
                    }
                    Minecraft.getInstance().cameraEntity.setYRot(PressurizedClient.Player.getYHeadRot() + (float) PressurizedClient.CamShake / 20);
                }
            }
        }

        @SubscribeEvent
        public static void OnLeave(PlayerEvent.PlayerLoggedOutEvent event) {
            if (event.getEntity() == Minecraft.getInstance().player) {
                PressurizedClient.OverPressured = false;
                PressurizedClient.ValidHelmet = false;
                PressurizedClient.ValidChestPlate = false;
                PressurizedClient.ValidLeggings = false;
                PressurizedClient.ValidBoots = false;
            }
        }

        @SubscribeEvent
        public static void PRespawned(PlayerEvent.PlayerRespawnEvent event) {
            assert Minecraft.getInstance().player != null;
            if (Objects.equals(event.getEntity().getName().toString(), Minecraft.getInstance().player.getName().toString())) {
                PressurizedClient.PressureBuildup = 0;
                PressurizedClient.BodyPressure = 0;
                PressurizedClient.OverPressured = false;
            }
        }
    }
}