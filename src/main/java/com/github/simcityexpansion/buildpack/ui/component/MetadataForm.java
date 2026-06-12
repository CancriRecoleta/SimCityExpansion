package com.github.simcityexpansion.buildpack.ui.component;

import java.util.List;
import java.util.function.BiConsumer;

import com.github.simcityexpansion.buildpack.BuildPack;
import com.github.simcityexpansion.buildpack.model.BuildingCategory;
import com.github.simcityexpansion.buildpack.model.BuildingMetadata;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Selector;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.network.chat.Component;

/**
 * .sk 元数据编辑表单：分类下拉 + 名称/价格/作者/描述/标签/岗位输入框，
 * 双向绑定到一个 {@link BuildingMetadata} 模型。
 */
public final class MetadataForm {

  private final UIElement root;
  private final Selector<BuildingCategory> categorySelector;
  private final TextField nameField;
  private final TextField amountField;
  private final TextField authorField;
  private final TextField descriptionField;
  private final TextField tagsField;
  private final TextField jobTypeField;

  private BuildingMetadata model = new BuildingMetadata();

  public MetadataForm() {
    root = new UIElement();
    root.addClass(BuildPack.cls("form"));
    root.layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
        .gapRow(3.0f).widthStretch());

    Label sectionTitle = new Label();
    sectionTitle.addClass(BuildPack.cls("form-title"));
    sectionTitle.setValue(Component.translatable("buildpack.form.title"));

    categorySelector = new Selector<>();
    categorySelector.addClass(BuildPack.cls("form-category"));
    categorySelector.setCandidates(List.of(BuildingCategory.values()));
    // 自定义渲染并对 null 安全：Selector 在设置 provider 时会立即用「当前选中值」试渲染一次，
    // 而此刻尚未 setSelected，值为 null，LDLib 自带的 UIElementProvider.text 会因此 NPE。
    categorySelector.setCandidateUIProvider(category -> {
      Label option = new Label();
      option.setValue(category == null ? Component.empty() : category.displayName());
      return option;
    });
    categorySelector.setSelected(BuildingCategory.OTHER);
    categorySelector.setOnValueChanged(category -> {
      if (category != null) {
        model.category = category;
      }
    });
    categorySelector.layout(layout -> layout.height(16.0f).flexGrow(1.0f));

    nameField = field((meta, value) -> meta.name = value);
    amountField = field((meta, value) -> meta.amount = value);
    authorField = field((meta, value) -> meta.author = value);
    descriptionField = field((meta, value) -> meta.description = value);
    tagsField = field((meta, value) -> meta.tags = value);
    jobTypeField = field((meta, value) -> meta.jobType = value);

    root.addChildren(
        sectionTitle,
        row("buildpack.form.category", categorySelector),
        row("buildpack.form.name", nameField),
        row("buildpack.form.amount", amountField),
        row("buildpack.form.author", authorField),
        row("buildpack.form.description", descriptionField),
        row("buildpack.form.tags", tagsField),
        row("buildpack.form.job_type", jobTypeField));
  }

  /** 返回表单根元素。 */
  public UIElement root() {
    return root;
  }

  /** 绑定新模型并刷新各输入框；{@code editable} 为 false 时全部置灰只读。 */
  public void setModel(BuildingMetadata meta, boolean editable) {
    this.model = meta;
    categorySelector.setSelected(meta.category);
    nameField.setText(meta.name);
    amountField.setText(meta.amount);
    authorField.setText(meta.author);
    descriptionField.setText(meta.description);
    tagsField.setText(meta.tags);
    jobTypeField.setText(meta.jobType);

    categorySelector.setActive(editable);
    nameField.setActive(editable);
    amountField.setActive(editable);
    authorField.setActive(editable);
    descriptionField.setActive(editable);
    tagsField.setActive(editable);
    jobTypeField.setActive(editable);
  }

  /** 当前绑定的模型。 */
  public BuildingMetadata model() {
    return model;
  }

  private TextField field(BiConsumer<BuildingMetadata, String> writer) {
    TextField field = new TextField();
    field.addClass(BuildPack.cls("form-field"));
    field.setAnyString();
    field.setTextResponder(value -> writer.accept(model, value));
    field.layout(layout -> layout.height(16.0f).flexGrow(1.0f));
    return field;
  }

  private UIElement row(String labelKey, UIElement control) {
    UIElement row = new UIElement();
    row.addClass(BuildPack.cls("form-row"));
    row.layout(layout -> layout.flexDirection(FlexDirection.ROW)
        .alignItems(AlignItems.CENTER).gapColumn(3.0f).widthStretch());

    Label label = new Label();
    label.addClass(BuildPack.cls("form-label"));
    label.setValue(Component.translatable(labelKey));
    label.layout(layout -> layout.width(44.0f));

    row.addChildren(label, control);
    return row;
  }
}
