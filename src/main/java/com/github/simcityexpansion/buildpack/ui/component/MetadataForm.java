package com.github.simcityexpansion.buildpack.ui.component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.github.simcityexpansion.buildpack.model.BuildingCategory;
import com.github.simcityexpansion.buildpack.model.BuildingMetadata;
import com.github.simcityexpansion.buildpack.ui.BuildPackTheme;
import com.github.simcityexpansion.buildpack.ui.ThemedButton;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * .sk 元数据编辑表单：分类（点击循环的 {@link ThemedButton}）+ 名称/价格/作者/描述/标签/岗位输入框
 * （{@link EditBox}）+「覆盖同名建筑」勾选框（{@link Checkbox}），双向绑定到一个
 * {@link BuildingMetadata} 模型。控件由宿主屏幕在 {@link #rebuild} 时创建并登记，
 * 标签文本由 {@link #renderLabels} 绘制。
 */
public final class MetadataForm {

  private static final int LABEL_W = 44;
  private static final int ROW_H = 16;
  private static final int ROW_GAP = 3;

  /** 一行标签：文本 + 绘制坐标。 */
  private record FieldLabel(Component text, int x, int y) {}

  private BuildingMetadata model = new BuildingMetadata();
  private boolean editable;
  private boolean overwrite;

  private Font font;
  private int titleX;
  private int titleY;
  private final List<FieldLabel> labels = new ArrayList<>();

  private ThemedButton categoryButton;
  private EditBox nameField;
  private EditBox amountField;
  private EditBox authorField;
  private EditBox descriptionField;
  private EditBox tagsField;
  private EditBox jobTypeField;
  private Checkbox overwriteCheckbox;

  /** 重建并放置全部表单控件（每次屏幕 init 调用一次），返回内容底部 y。 */
  public int rebuild(Font font, int x, int y, int width, Consumer<AbstractWidget> add) {
    this.font = font;
    labels.clear();
    titleX = x;
    titleY = y;

    int controlX = x + LABEL_W;
    int controlW = Math.max(20, width - LABEL_W);
    int cursor = y + font.lineHeight + ROW_GAP;

    categoryButton = new ThemedButton(controlX, cursor, controlW, ROW_H,
        model.category.displayName(), this::cycleCategory);
    add.accept(categoryButton);
    labels.add(new FieldLabel(Component.translatable("buildpack.form.category"), x, cursor));
    cursor += ROW_H + ROW_GAP;

    nameField = field("name", x, controlX, cursor, controlW, add, (m, v) -> m.name = v);
    cursor += ROW_H + ROW_GAP;
    amountField = field("amount", x, controlX, cursor, controlW, add, (m, v) -> m.amount = v);
    cursor += ROW_H + ROW_GAP;
    authorField = field("author", x, controlX, cursor, controlW, add, (m, v) -> m.author = v);
    cursor += ROW_H + ROW_GAP;
    descriptionField =
        field("description", x, controlX, cursor, controlW, add, (m, v) -> m.description = v);
    cursor += ROW_H + ROW_GAP;
    tagsField = field("tags", x, controlX, cursor, controlW, add, (m, v) -> m.tags = v);
    cursor += ROW_H + ROW_GAP;
    jobTypeField = field("job_type", x, controlX, cursor, controlW, add, (m, v) -> m.jobType = v);
    cursor += ROW_H + ROW_GAP;

    overwriteCheckbox = Checkbox.builder(Component.translatable("buildpack.form.overwrite"), font)
        .pos(x, cursor)
        .selected(overwrite)
        .onValueChange((checkbox, value) -> overwrite = value)
        .build();
    add.accept(overwriteCheckbox);
    cursor += ROW_H + ROW_GAP;

    applyModel();
    return cursor;
  }

  private EditBox field(String key, int labelX, int controlX, int y, int controlW,
      Consumer<AbstractWidget> add, BiConsumer<BuildingMetadata, String> writer) {
    EditBox box = new EditBox(font, controlX, y, controlW, ROW_H, Component.empty());
    box.setMaxLength(256);
    box.setResponder(value -> writer.accept(model, value));
    add.accept(box);
    labels.add(new FieldLabel(Component.translatable("buildpack.form." + key), labelX, y));
    return box;
  }

  /** 绑定新模型并刷新各输入框；{@code editable} 为 false 时全部置灰只读。 */
  public void setModel(BuildingMetadata meta, boolean editable) {
    this.model = meta;
    this.editable = editable;
    applyModel();
  }

  private void applyModel() {
    if (categoryButton == null) {
      return;
    }
    categoryButton.setMessage(model.category.displayName());
    nameField.setValue(model.name);
    amountField.setValue(model.amount);
    authorField.setValue(model.author);
    descriptionField.setValue(model.description);
    tagsField.setValue(model.tags);
    jobTypeField.setValue(model.jobType);

    categoryButton.active = editable;
    setEditable(nameField);
    setEditable(amountField);
    setEditable(authorField);
    setEditable(descriptionField);
    setEditable(tagsField);
    setEditable(jobTypeField);
    overwriteCheckbox.active = editable;
  }

  private void setEditable(EditBox field) {
    field.setEditable(editable);
    field.active = editable;
  }

  private void cycleCategory() {
    BuildingCategory[] values = BuildingCategory.values();
    model.category = values[(model.category.ordinal() + 1) % values.length];
    categoryButton.setMessage(model.category.displayName());
  }

  /** 安装时是否覆盖同名建筑。 */
  public boolean overwrite() {
    return overwrite;
  }

  /** 当前绑定的模型。 */
  public BuildingMetadata model() {
    return model;
  }

  /** 绘制分组标题与各行标签（控件本身由屏幕的 widget 渲染绘出）。 */
  public void renderLabels(GuiGraphics g) {
    if (font == null) {
      return;
    }
    g.drawString(font, Component.translatable("buildpack.form.title"),
        titleX, titleY, BuildPackTheme.TITLE, true);
    for (FieldLabel label : labels) {
      g.drawString(font, label.text(), label.x(),
          label.y() + (ROW_H - font.lineHeight) / 2, BuildPackTheme.LABEL, true);
    }
  }
}
