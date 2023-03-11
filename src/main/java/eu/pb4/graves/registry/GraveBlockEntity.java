package eu.pb4.graves.registry;

import eu.pb4.graves.GraveNetworking;
import eu.pb4.graves.config.Config;
import eu.pb4.graves.config.ConfigManager;
import eu.pb4.graves.grave.Grave;
import eu.pb4.graves.grave.GraveHolder;
import eu.pb4.graves.grave.GraveManager;
import eu.pb4.graves.grave.PositionedItemStack;
import eu.pb4.graves.other.Location;
import eu.pb4.graves.other.VanillaInventoryMask;
import eu.pb4.graves.other.VisualGraveData;
import eu.pb4.holograms.api.elements.SpacingHologramElement;
import eu.pb4.holograms.api.holograms.WorldHologram;
import eu.pb4.placeholders.api.Placeholders;
import eu.pb4.placeholders.api.node.EmptyNode;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.text.TextContent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.*;

import static eu.pb4.graves.registry.AbstractGraveBlock.IS_LOCKED;

public class GraveBlockEntity extends AbstractGraveBlockEntity implements GraveHolder {
    public static BlockEntityType<GraveBlockEntity> BLOCK_ENTITY_TYPE;
    public WorldHologram hologram = null;
    public BlockState replacedBlockState = Blocks.AIR.getDefaultState();
    private Grave data = null;
    private int dataRetrieveTries = 0;
    private VisualGraveData visualData = VisualGraveData.DEFAULT;
    private long graveId = -1;

    public GraveBlockEntity(BlockPos pos, BlockState state) {
        super(BLOCK_ENTITY_TYPE, pos, state);
    }

    public void setGrave(Grave grave, BlockState oldBlockState) {
        this.replacedBlockState = oldBlockState;
        this.setGrave(grave);
    }


    public void setGrave(Grave grave) {
        this.data = grave;

        if (grave != null) {
            this.visualData = grave.toVisualGraveData();
            this.graveId = grave.getId();
        } else {
            this.graveId = -1;
        }
        this.markDirty();
    }

    @Override
    protected void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        nbt.put("BlockState", NbtHelper.fromBlockState(this.replacedBlockState));
        nbt.put("VisualData", this.getClientData().toNbt());
        nbt.putLong("GraveId", this.graveId);
    }


    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        try {
            if (nbt.contains("GraveInfo", NbtElement.COMPOUND_TYPE)) {
                // Legacy grave handling
                this.data = new Grave();
                this.data.readNbt(nbt.getCompound("GraveInfo"));

                NbtList nbtList = nbt.getList("Items", NbtElement.COMPOUND_TYPE);

                for (NbtElement compound : nbtList) {
                    this.data.getItems().add(new PositionedItemStack(ItemStack.fromNbt((NbtCompound) compound), -1, VanillaInventoryMask.INSTANCE, null));
                }
                GraveManager.INSTANCE.add(this.data);
                this.visualData = this.data.toVisualGraveData();
            } else if (nbt.contains("GraveId", NbtElement.LONG_TYPE)) {
                this.graveId = nbt.getLong("GraveId");
            }

            if (this.data == null) {
                this.fetchGraveData();
            }

            if (this.visualData == null) {
                this.visualData = VisualGraveData.fromNbt(nbt.getCompound("VisualData"));
            }

            this.replacedBlockState = NbtHelper.toBlockState(Registries.BLOCK.getReadOnlyWrapper(), (NbtCompound) Objects.requireNonNull(nbt.get("BlockState")));
        } catch (Exception e) {
            this.visualData = VisualGraveData.DEFAULT;
        }
    }

    protected void fetchGraveData() {
        this.data = GraveManager.INSTANCE.getId(this.graveId);

        if (this.data != null) {
            this.visualData = this.data.toVisualGraveData();
            this.updateForAllPlayers();
            this.markDirty();
        }
    }

    protected void updateForAllPlayers() {
        assert this.world != null;

        var converter = ConfigManager.getConfig().style.converter;
        var rotation = this.getCachedState().get(Properties.ROTATION);
        var isProtected = this.data.isProtected();
        for (var player : ((ServerWorld) this.world).getChunkManager().threadedAnvilChunkStorage.getPlayersWatchingChunk(new ChunkPos(this.pos), false)) {
            if (!GraveNetworking.sendGrave(player.networkHandler, pos, isProtected, this.data.toVisualGraveData(), this.data.getPlaceholders(player.server), null)) {
                converter.sendNbt(player, this.getCachedState(), pos.toImmutable(), rotation, isProtected, this.data.toVisualGraveData(), this.data, null);
            }
        }
    }

    protected void updateForClientPlayers() {
        assert this.world != null;

        var isProtected = this.data.isProtected();
        for (var player : ((ServerWorld) this.world).getChunkManager().threadedAnvilChunkStorage.getPlayersWatchingChunk(new ChunkPos(this.pos), false)) {
            GraveNetworking.sendGrave(player.networkHandler, pos, isProtected, this.data.toVisualGraveData(), this.data.getPlaceholders(player.server), null);
        }

    }


    @Override
    public void markRemoved() {
        if (this.hologram != null) {
            this.hologram.hide();
        }
        this.hologram = null;
        super.markRemoved();
    }

    public static <T extends BlockEntity> void tick(World world, BlockPos pos, BlockState state, T t) {
        if (!(t instanceof GraveBlockEntity self) || world.isClient()) {
            return;
        }

        if (self.data == null) {
            if (world.getTime() % 10 == 0) {
                self.fetchGraveData();
            }
            return;
        }

        if (self.data.isRemoved()) {
            self.breakBlock();
            return;
        }

        if (world.getTime() % 5 != 0) {
            return;
        }

        Config config = ConfigManager.getConfig();

        Map<String, Text> placeholders = self.data.getPlaceholders(self.world.getServer());

        if (config.configData.breakingTime > -1 && self.data.shouldNaturallyBreak()) {
            world.setBlockState(pos, self.replacedBlockState, Block.NOTIFY_ALL);
            return;
        }

        boolean isProtected = state.get(IS_LOCKED);

        if (isProtected && !self.data.isProtected()) {
            world.setBlockState(pos, state.with(IS_LOCKED, false));
            isProtected = false;
            self.updateForAllPlayers();
        }

        var updateRate = config.style.converter.updateRate(state, pos, self.data.toVisualGraveData(), self.data);
        if (updateRate > 0 && world.getTime() % updateRate == 0) {
            self.updateForAllPlayers();
        } else if (updateRate <= 0 && world.getTime() % 20 == 0) {
            self.updateForClientPlayers();
        }

        if (config.configData.hologram) {
            if (self.hologram == null) {
                self.hologram = new WorldHologram((ServerWorld) world, new Vec3d(pos.getX(), pos.getY(), pos.getZ()).add(0.5, config.configData.hologramOffset, 0.5)) {
                    @Override
                    public boolean canAddPlayer(ServerPlayerEntity player) {
                        return config.configData.hologramDisplayIfOnClient || !GraveNetworking.canReceive(player.networkHandler);
                    }
                };
                self.hologram.show();
            }

            List<Text> texts = new ArrayList<>();

            for (var text : isProtected ? config.hologramProtectedText : config.hologramText) {
                if (text != EmptyNode.INSTANCE) {
                    texts.add(Placeholders.parseText(text, Placeholders.PREDEFINED_PLACEHOLDER_PATTERN, placeholders));
                } else {
                    texts.add(Text.empty());
                }
            }


            if (texts.size() != self.hologram.getElements().size()) {
                self.hologram.clearElements();
                for (Text text : texts) {
                    if (text.getContent() == TextContent.EMPTY && text.getSiblings().size() == 0) {
                        self.hologram.addElement(new SpacingHologramElement(0.28));
                    } else {
                        self.hologram.addText(text);
                    }
                }
            } else {
                int x = 0;
                for (Text text : texts) {
                    if (text.getContent() == TextContent.EMPTY && text.getSiblings().size() == 0) {
                        self.hologram.setElement(x, new SpacingHologramElement(0.28));
                    } else {
                        self.hologram.setText(x, text);
                    }
                    x++;
                }
            }


        } else {
            if (self.hologram != null) {
                self.hologram.hide();
                self.hologram = null;
            }
        }
    }

    public void breakBlock() {
        breakBlock(true);
    }

    public void breakBlock(boolean canCreateVisual) {
        if (canCreateVisual && ConfigManager.getConfig().configData.keepBlockAfterBreaking) {
            world.setBlockState(pos, VisualGraveBlock.INSTANCE.getStateWithProperties(this.getCachedState()), Block.NOTIFY_ALL | Block.FORCE_STATE);

            if (world.getBlockEntity(pos) instanceof VisualGraveBlockEntity blockEntity) {
                blockEntity.setVisualData(this.getClientData(), this.replacedBlockState, false);
            }

        } else {
            world.setBlockState(pos, this.replacedBlockState, Block.NOTIFY_ALL | Block.FORCE_STATE);
        }
    }


    @Nullable
    public Grave getGrave() {
        if (this.data == null) {
            this.fetchGraveData();
        }

        return this.data;
    }

    @Override
    public void setFromPacket(GraveNetworking.NetworkingGrave decoded) {
        this.clientText = decoded.displayText();
        this.visualData = decoded.data();
    }


    public VisualGraveData getClientData() {
        return this.data != null ? this.data.toVisualGraveData() : this.visualData;
    }
}
