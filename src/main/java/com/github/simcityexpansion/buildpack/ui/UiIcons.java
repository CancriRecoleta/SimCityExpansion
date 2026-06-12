package com.github.simcityexpansion.buildpack.ui;

import com.github.simcityexpansion.buildpack.model.BuildingCategory;
import com.github.simcityexpansion.buildpack.model.ImportFile;
import com.github.simcityexpansion.buildpack.model.InstalledBuilding;
import com.github.simcityexpansion.buildpack.model.PackArchive;
import com.github.simcityexpansion.buildpack.model.StructureFormat;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.RectTexture;
import org.joml.Vector4f;

/**
 * BuildPack 图标工厂：把文件/包/分类映射为彩色圆角方块图标。
 *
 * <p>刻意只用 {@link RectTexture}（程序化绘制）而非外部贴图，因此不依赖任何资源文件。
 */
public final class UiIcons {
  private UiIcons() {}

  // 暗底（Litematica 半透明黑面板）上的明亮图标色；直角方块贴近其像素图标风格。
  private static final int FOLDER_COLOR = 0xFFD8C480;
  private static final int NBT_COLOR = 0xFFFFB74D;
  private static final int LITEMATIC_COLOR = 0xFF4FC3F7;
  private static final int PACK_COLOR = 0xFFBA68C8;
  private static final float CORNER_RADIUS = 0.0f;

  /** 文件夹（分支节点）图标。 */
  public static IGuiTexture folder() {
    return square(FOLDER_COLOR);
  }

  /** 结构格式图标：原版 .nbt 橙色，投影青色。 */
  public static IGuiTexture format(StructureFormat format) {
    return square(format == StructureFormat.LITEMATIC ? LITEMATIC_COLOR : NBT_COLOR);
  }

  /** zip 拓展包图标。 */
  public static IGuiTexture pack() {
    return square(PACK_COLOR);
  }

  /** 建筑分类图标。 */
  public static IGuiTexture category(BuildingCategory category) {
    int color = switch (category) {
      case RESIDENTIAL -> 0xFFAED581;
      case COMMERCIAL -> 0xFF4DB6AC;
      case INDUSTRY -> 0xFFF06292;
      case PUBLIC -> 0xFF7986CB;
      case OTHER -> 0xFF90A4AE;
    };
    return square(color);
  }

  /** 树节点图标：按叶子内容类型着色，分支统一为文件夹色。 */
  public static IGuiTexture node(Object content) {
    if (content instanceof ImportFile file) {
      return format(file.format());
    }
    if (content instanceof PackArchive) {
      return pack();
    }
    if (content instanceof InstalledBuilding building) {
      return category(building.category());
    }
    return folder();
  }

  private static IGuiTexture square(int color) {
    return RectTexture.of(color).setRadius(new Vector4f(CORNER_RADIUS));
  }
}
