package vn.signa.catalog.internal;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

/**
 * Đọc {@code labels.yaml} (docs/05 §2). Đọc cây node thay vì dựng đối tượng để giữ số dòng của từng
 * mục và không bao giờ dựng lớp Java tuỳ ý từ file. Lỗi cấu trúc được gom rồi ném một {@link
 * LabelsFileException}; kiểm tra nội dung nằm ở {@link LabelMapValidator}.
 */
public final class LabelsYamlParser {

  private static final Pattern INTEGER = Pattern.compile("-?\\d{1,9}");

  private LabelsYamlParser() {}

  /** Đọc và kiểm tra file; sai thì ném {@link LabelsFileException}. */
  public static LabelMap load(Path path) {
    String content;
    try {
      content = Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Không đọc được danh mục nhãn " + path, e);
    }
    String source = path.toString();
    return LabelMapValidator.validate(parse(content, source), source);
  }

  /** Chỉ đọc cấu trúc, chưa kiểm tra nội dung. */
  public static LabelMap parse(String content, String source) {
    return new Run(source).parse(new StringReader(content));
  }

  private static final class Run {
    private final String source;
    private final List<String> problems = new ArrayList<>();

    Run(String source) {
      this.source = source;
    }

    LabelMap parse(Reader reader) {
      Node root;
      try {
        root = new Yaml(new SafeConstructor(new LoaderOptions())).compose(reader);
      } catch (YAMLException e) {
        throw new LabelsFileException(source, List.of("YAML sai cú pháp: " + e.getMessage()));
      }
      if (!(root instanceof MappingNode top)) {
        throw new LabelsFileException(source, List.of("dòng 1: file phải là một mapping"));
      }

      Integer version = null;
      List<LabelMap.Category> categories = new ArrayList<>();
      List<LabelMap.LabelClass> classes = new ArrayList<>();
      boolean hasCategories = false;
      boolean hasClasses = false;
      for (NodeTuple tuple : top.getValue()) {
        switch (key(tuple)) {
          case "version" -> version = integer(tuple.getValueNode(), "version");
          case "categories" -> {
            hasCategories = true;
            categories = categories(tuple.getValueNode());
          }
          case "classes" -> {
            hasClasses = true;
            classes = classes(tuple.getValueNode());
          }
          default -> {
            // Khoá khác (chú thích, trường mở rộng) được bỏ qua.
          }
        }
      }
      if (version == null) problems.add("dòng 1: thiếu `version`");
      if (!hasCategories) problems.add("dòng 1: thiếu `categories`");
      if (!hasClasses) problems.add("dòng 1: thiếu `classes`");
      if (!problems.isEmpty()) throw new LabelsFileException(source, problems);
      return new LabelMap(version, categories, classes);
    }

    private List<LabelMap.Category> categories(Node node) {
      List<LabelMap.Category> result = new ArrayList<>();
      if (!(node instanceof MappingNode map)) {
        problems.add(at(node) + "`categories` phải là mapping `khoá: { name: … }`");
        return result;
      }
      int order = 0;
      for (NodeTuple tuple : map.getValue()) {
        order++;
        String key = key(tuple);
        Node value = tuple.getValueNode();
        String name = null;
        if (value instanceof MappingNode fields) {
          for (NodeTuple field : fields.getValue()) {
            if ("name".equals(key(field))) name = text(field.getValueNode(), "name");
          }
        } else {
          problems.add(at(value) + "nhóm `" + key + "` phải là mapping có `name`");
        }
        result.add(
            new LabelMap.Category(
                key, key.toUpperCase(Locale.ROOT), name, order, line(tuple.getKeyNode())));
      }
      return result;
    }

    private List<LabelMap.LabelClass> classes(Node node) {
      List<LabelMap.LabelClass> result = new ArrayList<>();
      if (!(node instanceof SequenceNode list)) {
        problems.add(at(node) + "`classes` phải là danh sách");
        return result;
      }
      for (Node item : list.getValue()) {
        if (!(item instanceof MappingNode fields)) {
          problems.add(at(item) + "mỗi lớp phải là mapping");
          continue;
        }
        Integer id = null;
        Integer speed = null;
        String code = null;
        String category = null;
        String name = null;
        for (NodeTuple field : fields.getValue()) {
          Node value = field.getValueNode();
          switch (key(field)) {
            case "id" -> id = integer(value, "id");
            case "code" -> code = text(value, "code");
            case "category" -> category = text(value, "category");
            case "name" -> name = text(value, "name");
            case "speed_kmh" -> speed = integer(value, "speed_kmh");
            default -> {
              // Trường khác được bỏ qua.
            }
          }
        }
        result.add(new LabelMap.LabelClass(id, code, category, name, speed, line(item)));
      }
      return result;
    }

    private String key(NodeTuple tuple) {
      return tuple.getKeyNode() instanceof ScalarNode s ? s.getValue() : "";
    }

    private String text(Node node, String field) {
      if (node instanceof ScalarNode s && !s.getValue().isBlank()) return s.getValue().trim();
      problems.add(at(node) + "`" + field + "` phải là chuỗi không rỗng");
      return null;
    }

    private Integer integer(Node node, String field) {
      if (node instanceof ScalarNode s && INTEGER.matcher(s.getValue()).matches()) {
        return Integer.valueOf(s.getValue());
      }
      problems.add(at(node) + "`" + field + "` phải là số nguyên");
      return null;
    }
  }

  static int line(Node node) {
    return node.getStartMark().getLine() + 1;
  }

  static String at(Node node) {
    return "dòng " + line(node) + ": ";
  }
}
