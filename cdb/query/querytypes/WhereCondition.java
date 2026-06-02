package cdb.query.querytypes;

import java.util.List;

public class WhereCondition {
    private final String column;
    private final String op;
    private final String value;
    private final String secondValue; // for BETWEEN
    private final List<String> valueList; // for IN
    private final boolean negated; // for NOT

    public WhereCondition(String column, String op, String value) {
        this(column, op, value, null, null, false);
    }

    public WhereCondition(String column, String op, String value, String secondValue,
                          List<String> valueList, boolean negated) {
        this.column = column;
        this.op = op;
        this.value = value;
        this.secondValue = secondValue;
        this.valueList = valueList;
        this.negated = negated;
    }

    public String getColumn()     { return column; }
    public String getOp()         { return op; }
    public String getValue()      { return value; }
    public String getSecondValue(){ return secondValue; }
    public List<String> getValueList() { return valueList; }
    public boolean isNegated()    { return negated; }

    public boolean isIn()         { return "IN".equals(op); }
    public boolean isNotIn()      { return "NOT_IN".equals(op); }
    public boolean isBetween()    { return "BETWEEN".equals(op); }
    public boolean isLike()       { return "LIKE".equals(op); }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (negated) sb.append("NOT ");
        sb.append(column).append(" ").append(op).append(" ").append(value);
        if (secondValue != null) sb.append(" AND ").append(secondValue);
        return sb.toString();
    }
}
