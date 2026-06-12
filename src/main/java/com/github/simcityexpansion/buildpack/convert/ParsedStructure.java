package com.github.simcityexpansion.buildpack.convert;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;

import com.github.simcityexpansion.buildpack.model.StructureFormat;
import com.github.simcityexpansion.buildpack.model.StructureInfo;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/**
 * 一次读盘内的完整解析结果：摘要（信息面板/元数据预填）+ 方块数据
 * （材料清单、俯视图、安装转换）。客户端界面与服务端命令共用。
 *
 * @param info 只读摘要
 * @param structure 完整中间模型
 */
public record ParsedStructure(StructureInfo info, NbtStructure structure) {

  /** 按格式解析一个结构文件（gzip NBT 只读一次）。 */
  public static ParsedStructure parse(Path path, StructureFormat format) throws IOException {
    return parse(NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap()), format);
  }

  /** 从内存字节解析（zip 拓展包内的条目走这里，无需落盘）。 */
  public static ParsedStructure parse(byte[] gzipBytes, StructureFormat format)
      throws IOException {
    return parse(NbtIo.readCompressed(
        new ByteArrayInputStream(gzipBytes), NbtAccounter.unlimitedHeap()), format);
  }

  /** 从已读出的根标签解析。 */
  public static ParsedStructure parse(CompoundTag root, StructureFormat format)
      throws IOException {
    return switch (format) {
      case LITEMATIC -> new ParsedStructure(
          LitematicReader.readInfo(root), LitematicReader.readAndMerge(root));
      case VANILLA_NBT -> {
        NbtStructure structure = StructureNbtReader.read(root);
        yield new ParsedStructure(StructureNbtReader.summarize(structure), structure);
      }
      case SCHEM -> {
        NbtStructure structure = SchemReader.read(root);
        yield new ParsedStructure(StructureNbtReader.summarize(structure), structure);
      }
    };
  }
}
