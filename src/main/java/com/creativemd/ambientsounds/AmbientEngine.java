package com.creativemd.ambientsounds;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

import org.apache.commons.io.IOUtils;

import com.creativemd.ambientsounds.AmbientEnviroment.BiomeArea;
import com.creativemd.creativecore.common.utils.type.Pair;
import com.creativemd.creativecore.common.utils.type.PairList;
import com.google.common.base.Charsets;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;

import net.minecraft.block.BlockLeaves;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IResource;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockPos.MutableBlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;

public class AmbientEngine {
	
	public static final ResourceLocation engineLocation = new ResourceLocation(AmbientSounds.modid, "engine.json");
	private static final JsonParser parser = new JsonParser();
	private static final Gson gson = generateGson();
	
	private static Gson generateGson() {
		GsonBuilder builder = new GsonBuilder();
		builder.registerTypeAdapter(ResourceLocation.class, new JsonDeserializer<ResourceLocation>() {
			
			@Override
			public ResourceLocation deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
				if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isString())
					return new ResourceLocation(json.getAsString());
				return null;
			}
		});
		return builder.create();
	}
	
	public static AmbientEngine loadAmbientEngine(AmbientSoundEngine soundEngine) {
		AmbientSounds.config.load();
		
		IResource resource;
		try {
			resource = Minecraft.getMinecraft().getResourceManager().getResource(engineLocation);
			JsonObject root = parser.parse(IOUtils.toString(resource.getInputStream(), Charsets.UTF_8)).getAsJsonObject();
			
			AmbientEngine engine = gson.fromJson(root, AmbientEngine.class);
			engine.init();
			
			AmbientSounds.logger.info("Successfully loaded sound engine. %s dimension(s) and %s region(s)", engine.dimensions.length, engine.allRegions.size());
			engine.soundEngine = soundEngine;
			
			return engine;
			
		} catch (Exception e) {
			e.printStackTrace();
			AmbientSounds.logger.error("Sound engine crashed, no sounds will be played!");
		}
		
		AmbientSounds.config.save();
		return null;
	}
	
	protected transient PairList<String, AmbientRegion> allRegions = new PairList<>();
	protected transient PairList<String, AmbientRegion> generalRegions = new PairList<>();
	protected transient List<AmbientRegion> activeRegions = new ArrayList<>();
	protected transient AmbientSoundEngine soundEngine;
	
	public AmbientRegion getRegion(String name) {
		return allRegions.getValue(name);
	}
	
	public AmbientDimension[] dimensions;
	public AmbientRegion[] regions;
	
	@SerializedName(value = "enviroment-tick-time")
	public int enviromentTickTime = 40;
	@SerializedName(value = "sound-tick-time")
	public int soundTickTime = 10;
	@SerializedName(value = "block-scan-distance")
	public int blockScanDistance = 40;
	
	@SerializedName(value = "average-height-scan-distance")
	public int averageHeightScanDistance = 2;
	@SerializedName(value = "average-height-scan-count")
	public int averageHeightScanCount = 5;
	
	@SerializedName(value = "biome-scan-distance")
	public int biomeScanDistance = 5;
	@SerializedName(value = "biome-scan-count")
	public int biomeScanCount = 3;
	
	public AmbientDimension getDimension(World world) {
		for (int i = 0; i < dimensions.length; i++) {
			if (dimensions[i].is(world))
				return dimensions[i];
		}
		return null;
	}
	
	public void stopEngine() {
		if (!activeRegions.isEmpty()) {
			for (AmbientRegion region : activeRegions)
				region.deactivate();
			activeRegions.clear();
		}
	}
	
	private boolean checkRegion(AmbientDimension dimension, int i, AmbientRegion region) {
		if (region.name == null || region.name.isEmpty()) {
			if (dimension == null)
				AmbientSounds.logger.error("Found invalid region at index={0}", i);
			else
				AmbientSounds.logger.error("Found invalid region in '{0}' at index={1}", dimension.name, i);
			return false;
		}
		return true;
	}
	
	public void init() {
		for (int i = 0; i < dimensions.length; i++) {
			AmbientDimension dimension = dimensions[i];
			if (dimension.name == null || dimension.name.isEmpty())
				throw new RuntimeException("Invalid dimension name at index=" + i);
			
			if (dimension.regions != null)
				for (int j = 0; j < dimension.regions.length; j++) {
					AmbientRegion region = dimension.regions[j];
					if (checkRegion(dimension, j, region))
						allRegions.add(region.name, region);
				}
		}
		
		for (int i = 0; i < regions.length; i++) {
			AmbientRegion region = regions[i];
			if (checkRegion(null, i, region)) {
				allRegions.add(region.name, region);
				generalRegions.add(region.name, region);
			}
		}
		
		for (AmbientDimension dimension : dimensions)
			dimension.init(this);
		
		for (AmbientRegion region : allRegions.values()) {
			region.init(this);
		}
	}
	
	public void tick(AmbientEnviroment env) {
		if (env.dimension.regions != null)
			for (AmbientRegion region : env.dimension.regions) {
				if (region.tick(env)) {
					if (!region.isActive()) {
						region.activate();
						activeRegions.add(region);
					}
				} else if (region.isActive()) {
					region.deactivate();
					activeRegions.remove(region);
				}
			}
		
		for (AmbientRegion region : generalRegions.values()) {
			if (region.tick(env)) {
				if (!region.isActive()) {
					region.activate();
					activeRegions.add(region);
				}
			} else if (region.isActive()) {
				region.deactivate();
				activeRegions.remove(region);
			}
		}
		
		soundEngine.tick();
	}
	
	public void fastTick() {
		if (!activeRegions.isEmpty()) {
			for (Iterator iterator = activeRegions.iterator(); iterator.hasNext();) {
				AmbientRegion region = (AmbientRegion) iterator.next();
				if (!region.fastTick()) {
					region.deactivate();
					iterator.remove();
				}
			}
		}
	}
	
	public double calculateAverageHeight(World world, EntityPlayer player) {
		int sum = 0;
		int count = 0;
		
		MutableBlockPos pos = new MutableBlockPos();
		
		for (int x = -averageHeightScanCount; x <= averageHeightScanCount; x++) {
			for (int z = -averageHeightScanCount; z <= averageHeightScanCount; z++) {
				
				pos.setPos(player);
				pos.add(averageHeightScanDistance, 0, averageHeightScanDistance);
				int height = getHeightBlock(world, pos);
				
				sum += height;
				count++;
			}
		}
		return (double) sum / count;
	}
	
	public PairList<BiomeArea, Float> calculateBiomes(World world, EntityPlayer player, double volume) {
		PairList<BiomeArea, Float> biomes = new PairList<>();
		
		if (world.provider.getDimension() == -1 || world.provider.getDimension() == 1)
			volume = 1F;
		
		if (volume > 0.0) {
			
			int posX = (int) player.posX;
			int posZ = (int) player.posZ;
			BlockPos center = new BlockPos(posX, 0, posZ);
			MutableBlockPos pos = new MutableBlockPos();
			for (int x = -biomeScanCount; x <= biomeScanCount; x++) {
				for (int z = -biomeScanCount; z <= biomeScanCount; z++) {
					pos.setPos(posX + x * biomeScanDistance, 0, posZ + z * biomeScanDistance);
					Biome biome = world.getBiome(pos);
					
					float biomeVolume = (float) ((1 - Math.sqrt(center.distanceSq(pos)) / (biomeScanCount * biomeScanDistance * 2)) * volume);
					BiomeArea area = new BiomeArea(biome, pos);
					if (biomes.containsKey(area))
						biomes.set(area, Math.max(biomes.getValue(area), biomeVolume));
					else
						biomes.add(area, biomeVolume);
				}
			}
			
			biomes.sort(new Comparator<Pair<BiomeArea, Float>>() {
				
				@Override
				public int compare(Pair<BiomeArea, Float> o1, Pair<BiomeArea, Float> o2) {
					return o1.value.compareTo(o2.value);
				}
			});
		}
		return biomes;
	}
	
	public static int getHeightBlock(World world, MutableBlockPos pos) {
		int y;
		int heighest = 2;
		
		for (y = 45; y < 256; ++y) {
			pos.setY(y);
			IBlockState state = world.getBlockState(pos);
			if ((state.isOpaqueCube() && !(state.getBlock() instanceof BlockLeaves)) || state.getBlock() == Blocks.WATER)
				heighest = y;
		}
		
		return heighest;
	}
	
}
