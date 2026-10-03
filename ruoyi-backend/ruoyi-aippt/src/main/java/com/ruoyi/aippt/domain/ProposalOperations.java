package com.ruoyi.aippt.domain;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.util.Set;
import java.util.UUID;
/** 单项采纳只改该操作涉及的块与叶，保留其他尚未采纳候选和当前几何。 */
public final class ProposalOperations  {
    private ProposalOperations()  {
    }
    public static void apply(JSONObject slide, JSONObject op, boolean undo)  {
        String operation = op.getString("op");
        if (operation == null) operation = "replace";
        boolean flex = "flex".equals(slide.getString("layout_mode"));
        AipptRules.require(Set.of("replace", "add", "delete", "change_type").contains(operation), "未知候选操作");
        AipptRules.require(flex || "replace".equals(operation), "固定页仅允许内容替换");
        String blockId = op.getString("block_id");
        JSONArray blocks = slide.getJSONArray("blocks");
        JSONObject current = blocks.stream().map(JSONObject.class::cast) .filter(block -> blockId.equals(block.getString("id"))).findFirst().orElse(null);
        JSONObject value = op.getJSONObject(undo ? "before" : "after");
        if (current != null) AipptRules.require(!current.getBooleanValue("locked"), "AI不能修改锁定块");
        if (value == null)  {
            AipptRules.require(current != null && blocks.size() > 1, "不能删除不存在或最后一个块");
            blocks.remove(current);
            removeLeaf(slide.getJSONObject("layout_tree"), blockId);
            clearOverrides(slide.getJSONObject("layout_tree"));
            return;
        }
        JSONObject next = current == null ? AipptRules.copy(value) : AipptRules.copy(current);
        if ("change_type".equals(operation))  {
            next = AipptRules.copy(value);
            next.put("slot_id", current == null ? blockId : current.getString("slot_id"));
            next.put("style", current == null ? null : current.get("style"));
        }
        else  {
            JSONObject content = AipptRules.copy(value);
            content.remove("block_id");
            content.remove("id");
            content.remove("slot_id");
            content.remove("locked");
            content.remove("style");
            if (current != null) AipptRules.require(current.getString("type").equals(content.getString("type")), "替换类型与当前块不匹配");
            next.putAll(content);
        }
        next.remove("block_id");
        next.put("id", blockId);
        if (next.getString("slot_id") == null) next.put("slot_id", blockId);
        next.put("locked", false);
        AipptRules.block(next);
        if (current == null)  {
            blocks.add(next);
            JSONObject leaf = JSONObject.of("type", "block", "id", UUID.randomUUID().toString(), "block_id", blockId, "grow", 1);
            setTextStyle(leaf, next.getString("type"));
            JSONObject tree = slide.getJSONObject("layout_tree");
            String anchor = op.getString("after_block_id");
            if (!insertAfter(tree, anchor, leaf))  {
                tree.getJSONArray("children").add(0, leaf);
                insertRatio(tree, 0);
            }
            clearOverrides(tree);
        }
        else  {
            blocks.set(blocks.indexOf(current), next);
            if ("change_type".equals(operation))  {
                updateTextStyle(slide.getJSONObject("layout_tree"), blockId, next.getString("type"));
                clearOverrides(slide.getJSONObject("layout_tree"));
            }
        }
    }
    public static boolean removeLeaf(JSONObject node, String blockId)  {
        if (node == null || node.getJSONArray("children") == null) return false;
        JSONArray children = node.getJSONArray("children");
        for (int i = 0; i < children.size(); i++)  {
            JSONObject child = children.getJSONObject(i);
            if ("block".equals(child.getString("type")) && blockId.equals(child.getString("block_id")))  {
                children.remove(i);
                removeRatio(node, i);
                return true;
            }
            if (removeLeaf(child, blockId))  {
                // 原本的空容器是人工留白，只有本操作清空的容器才移除。
                if (child.getJSONArray("children") != null && child.getJSONArray("children").isEmpty())  {
                    children.remove(i);
                    removeRatio(node, i);
                }
                return true;
            }
        }
        return false;
    }
    private static boolean insertAfter(JSONObject node, String anchor, JSONObject leaf)  {
        if (node == null || node.getJSONArray("children") == null) return false;
        JSONArray children = node.getJSONArray("children");
        for (int i = 0; i < children.size(); i++)  {
            JSONObject child = children.getJSONObject(i);
            if (anchor != null && anchor.equals(child.getString("block_id")))  {
                children.add(i + 1, leaf);
                insertRatio(node, i + 1);
                return true;
            }
            if (insertAfter(child, anchor, leaf)) return true;
        }
        return false;
    }
    private static void removeRatio(JSONObject node, int index)  {
        JSONArray ratios = node.getJSONArray("ratios");
        if (ratios == null) return;
        ratios.remove(index);
        if (ratios.isEmpty()) node.remove("ratios");
    }
    public static void insertRatio(JSONObject node, int index)  {
        JSONArray ratios = node.getJSONArray("ratios");
        if (ratios == null) return;
        double sum = ratios.stream().mapToDouble(raw -> ((Number) raw).doubleValue()).sum();
        ratios.add(index, ratios.isEmpty() ? 1d : sum / ratios.size());
    }
    public static void clearOverrides(JSONObject node)  {
        if (node == null) return;
        node.remove("rect_override");
        JSONArray children = node.getJSONArray("children");
        if (children != null) for (Object child : children) clearOverrides((JSONObject) child);
    }
    private static void updateTextStyle(JSONObject node, String blockId, String type)  {
        if (node == null) return;
        if (blockId.equals(node.getString("block_id"))) setTextStyle(node, type);
        JSONArray children = node.getJSONArray("children");
        if (children != null) for (Object child : children) updateTextStyle((JSONObject) child, blockId, type);
    }
    private static void setTextStyle(JSONObject leaf, String type)  {
        if ("bullets".equals(type)) leaf.put("text_style", "bullet");
        else if (Set.of("text", "cards", "callout").contains(type)) leaf.put("text_style", "body");
        else leaf.remove("text_style");
    }
}
