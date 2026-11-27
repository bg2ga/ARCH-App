package cn.arch.dmr;

/** [3.2.39] 网络收音机电台条目。 */
public final class RadioStation {
    public final String id;
    public final String name;
    public final String province;
    public final String city;
    public final String category;
    public final String sourceType;
    public final String stream;

    public RadioStation(String id, String name, String province, String city,
                        String category, String sourceType, String stream) {
        this.id = id == null ? "" : id;
        this.name = name == null ? "" : name;
        this.province = province == null ? "" : province;
        this.city = city == null ? "" : city;
        this.category = category == null ? "" : category;
        this.sourceType = sourceType == null ? "" : sourceType;
        this.stream = stream == null ? "" : stream;
    }

    public String subtitle() {
        StringBuilder sb = new StringBuilder();
        if (!province.isEmpty()) sb.append(province);
        if (!city.isEmpty() && !city.equals(province)) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(city);
        }
        if (!category.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(category);
        }
        return sb.toString();
    }
}
