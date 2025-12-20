package com.elara.script.plugins;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.elara.script.ElaraScript;

/**
 * ElaraMatrixPlugin
 *
 * Matrix operations for ElaraScript v2.
 *
 * Design:
 * - Matrices are represented as ARRAY of ARRAY of NUMBER/NULL
 *   (i.e., a Value.ARRAY whose elements are Value.ARRAY rows).
 * - Operations are pure; output is a new matrix/array.
 * - Ragged matrices are rejected unless otherwise stated.
 * - NULL elements propagate (any op touching null yields null).
 *
 * Functions:
 * - mat_shape(m) -> [rows, cols]
 * - mat_scalar_mul(m, k) -> m*k
 * - mat_scalar_add(m, k) -> m+k
 * - mat_add(a, b) -> a+b
 * - mat_sub(a, b) -> a-b
 * - mat_transpose(m) -> m^T
 * - mat_mul(a, b) -> a*b
 * - mat_vec_mul(m, v) -> m*v (v is 1D array)
 * - mat_dot(a, b) -> dot(a, b) (vectors)
 */
public final class ElaraMatrixPlugin {

    private ElaraMatrixPlugin() {}

    public static void register(ElaraScript engine) {
        engine.registerFunction("mat_shape", args -> {
            requireArgs("mat_shape", args, 1);
            Matrix m = requireMatrix(args.get(0), "mat_shape", 0);
            return ElaraScript.Value.array(List.of(
                    ElaraScript.Value.number(m.rows),
                    ElaraScript.Value.number(m.cols)
            ));
        });

        engine.registerFunction("mat_scalar_mul", args -> {
            requireArgs("mat_scalar_mul", args, 2);
            Matrix m = requireMatrix(args.get(0), "mat_scalar_mul", 0);
            double k = requireNumber(args.get(1), "mat_scalar_mul", 1);
            return toValue(m.scalarMul(k));
        });

        engine.registerFunction("mat_scalar_add", args -> {
            requireArgs("mat_scalar_add", args, 2);
            Matrix m = requireMatrix(args.get(0), "mat_scalar_add", 0);
            double k = requireNumber(args.get(1), "mat_scalar_add", 1);
            return toValue(m.scalarAdd(k));
        });

        engine.registerFunction("mat_add", args -> {
            requireArgs("mat_add", args, 2);
            Matrix a = requireMatrix(args.get(0), "mat_add", 0);
            Matrix b = requireMatrix(args.get(1), "mat_add", 1);
            return toValue(a.add(b));
        });

        engine.registerFunction("mat_sub", args -> {
            requireArgs("mat_sub", args, 2);
            Matrix a = requireMatrix(args.get(0), "mat_sub", 0);
            Matrix b = requireMatrix(args.get(1), "mat_sub", 1);
            return toValue(a.sub(b));
        });

        engine.registerFunction("mat_transpose", args -> {
            requireArgs("mat_transpose", args, 1);
            Matrix m = requireMatrix(args.get(0), "mat_transpose", 0);
            return toValue(m.transpose());
        });

        engine.registerFunction("mat_mul", args -> {
            requireArgs("mat_mul", args, 2);
            Matrix a = requireMatrix(args.get(0), "mat_mul", 0);
            Matrix b = requireMatrix(args.get(1), "mat_mul", 1);
            return toValue(a.mul(b));
        });

        engine.registerFunction("mat_vec_mul", args -> {
            requireArgs("mat_vec_mul", args, 2);
            Matrix m = requireMatrix(args.get(0), "mat_vec_mul", 0);
            Vector v = requireVector(args.get(1), "mat_vec_mul", 1);
            return ElaraScript.Value.array(m.mulVec(v).values);
        });

        engine.registerFunction("mat_dot", args -> {
            requireArgs("mat_dot", args, 2);
            Vector a = requireVector(args.get(0), "mat_dot", 0);
            Vector b = requireVector(args.get(1), "mat_dot", 1);
            return ElaraScript.Value.number(dot(a, b));
        });
    }

    // ===================== INTERNAL TYPES =====================

    /** Dense matrix with nullable elements. */
    private static final class Matrix {
        final int rows;
        final int cols;
        final Double[][] data; // null means Elara nil

        Matrix(int rows, int cols) {
            this.rows = rows;
            this.cols = cols;
            this.data = new Double[rows][cols];
        }

        Matrix(Double[][] data) {
            this.rows = data.length;
            this.cols = (rows == 0) ? 0 : data[0].length;
            this.data = data;
        }

        Matrix scalarMul(double k) {
            Double[][] out = new Double[rows][cols];
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    Double x = data[r][c];
                    out[r][c] = (x == null) ? null : (x * k);
                }
            }
            return new Matrix(out);
        }

        Matrix scalarAdd(double k) {
            Double[][] out = new Double[rows][cols];
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    Double x = data[r][c];
                    out[r][c] = (x == null) ? null : (x + k);
                }
            }
            return new Matrix(out);
        }

        Matrix add(Matrix b) {
            requireSameShape(this, b, "mat_add");
            Double[][] out = new Double[rows][cols];
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    Double x = data[r][c];
                    Double y = b.data[r][c];
                    out[r][c] = (x == null || y == null) ? null : (x + y);
                }
            }
            return new Matrix(out);
        }

        Matrix sub(Matrix b) {
            requireSameShape(this, b, "mat_sub");
            Double[][] out = new Double[rows][cols];
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    Double x = data[r][c];
                    Double y = b.data[r][c];
                    out[r][c] = (x == null || y == null) ? null : (x - y);
                }
            }
            return new Matrix(out);
        }

        Matrix transpose() {
            Double[][] out = new Double[cols][rows];
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    out[c][r] = data[r][c];
                }
            }
            return new Matrix(out);
        }

        Matrix mul(Matrix b) {
            if (this.cols != b.rows) {
                throw new RuntimeException("mat_mul: shape mismatch (" + rows + "x" + cols + ") * (" + b.rows + "x" + b.cols + ")");
            }
            Double[][] out = new Double[this.rows][b.cols];
            for (int r = 0; r < this.rows; r++) {
                for (int c = 0; c < b.cols; c++) {
                    Double acc = 0.0;
                    boolean anyNull = false;
                    for (int k = 0; k < this.cols; k++) {
                        Double x = this.data[r][k];
                        Double y = b.data[k][c];
                        if (x == null || y == null) {
                            anyNull = true;
                            break;
                        }
                        acc += x * y;
                    }
                    out[r][c] = anyNull ? null : acc;
                }
            }
            return new Matrix(out);
        }

        Vector mulVec(Vector v) {
            if (this.cols != v.n) {
                throw new RuntimeException("mat_vec_mul: shape mismatch (" + rows + "x" + cols + ") * (" + v.n + ")");
            }
            List<ElaraScript.Value> out = new ArrayList<>(Collections.nCopies(this.rows, ElaraScript.Value.nil()));
            for (int r = 0; r < this.rows; r++) {
                double acc = 0.0;
                boolean anyNull = false;
                for (int c = 0; c < this.cols; c++) {
                    Double x = this.data[r][c];
                    Double y = v.data[c];
                    if (x == null || y == null) { anyNull = true; break; }
                    acc += x * y;
                }
                if (!anyNull) out.set(r, ElaraScript.Value.number(acc));
            }
            return new Vector(out);
        }
    }

    /** Vector of nullable elements. */
    private static final class Vector {
        final int n;
        final Double[] data;
        final List<ElaraScript.Value> values; // original-ish for easy return

        Vector(List<ElaraScript.Value> values) {
            this.n = values.size();
            this.data = new Double[n];
            this.values = values;
            for (int i = 0; i < n; i++) {
                this.data[i] = asDoubleOrNull(values.get(i));
            }
        }
    }

    private static double dot(Vector a, Vector b) {
        if (a.n != b.n) throw new RuntimeException("mat_dot: length mismatch " + a.n + " vs " + b.n);
        double acc = 0.0;
        for (int i = 0; i < a.n; i++) {
            Double x = a.data[i];
            Double y = b.data[i];
            if (x == null || y == null) return Double.NaN; // explicit signal; caller can handle
            acc += x * y;
        }
        return acc;
    }

    // ===================== CONVERSION =====================

    private static ElaraScript.Value toValue(Matrix m) {
        List<ElaraScript.Value> rows = new ArrayList<>(m.rows);
        for (int r = 0; r < m.rows; r++) {
            List<ElaraScript.Value> row = new ArrayList<>(m.cols);
            for (int c = 0; c < m.cols; c++) {
                Double x = m.data[r][c];
                row.add(x == null ? ElaraScript.Value.nil() : ElaraScript.Value.number(x));
            }
            rows.add(ElaraScript.Value.array(row));
        }
        return ElaraScript.Value.array(rows);
    }

    // ===================== ARG VALIDATION =====================

    private static void requireArgs(String fn, List<ElaraScript.Value> args, int n) {
        if (args.size() != n) {
            throw new RuntimeException(fn + "() expects " + n + " arguments, got " + args.size());
        }
    }

    private static double requireNumber(ElaraScript.Value v, String fn, int idx) {
        if (v.getType() != ElaraScript.Value.Type.NUMBER) {
            throw new RuntimeException(fn + " arg[" + idx + "] must be a number");
        }
        return v.asNumber();
    }

    private static Matrix requireMatrix(ElaraScript.Value v, String fn, int idx) {
        if (v.getType() != ElaraScript.Value.Type.ARRAY) {
            throw new RuntimeException(fn + " arg[" + idx + "] must be a matrix (array of arrays)");
        }
        List<ElaraScript.Value> rows = v.asArray();
        int rCount = rows.size();
        if (rCount == 0) return new Matrix(0, 0);

        // validate first row
        if (rows.get(0).getType() != ElaraScript.Value.Type.ARRAY) {
            throw new RuntimeException(fn + " arg[" + idx + "] must be a matrix (array of arrays)");
        }
        int cCount = rows.get(0).asArray().size();

        Double[][] data = new Double[rCount][cCount];

        for (int r = 0; r < rCount; r++) {
            ElaraScript.Value rowV = rows.get(r);
            if (rowV.getType() != ElaraScript.Value.Type.ARRAY) {
                throw new RuntimeException(fn + " arg[" + idx + "] row[" + r + "] is not an array");
            }
            List<ElaraScript.Value> row = rowV.asArray();
            if (row.size() != cCount) {
                throw new RuntimeException(fn + " arg[" + idx + "] is ragged (row " + r + " has " + row.size() + ", expected " + cCount + ")");
            }
            for (int c = 0; c < cCount; c++) {
                ElaraScript.Value cell = row.get(c);
                if (cell.getType() == ElaraScript.Value.Type.NULL) {
                    data[r][c] = null;
                } else if (cell.getType() == ElaraScript.Value.Type.NUMBER) {
                    data[r][c] = cell.asNumber();
                } else {
                    throw new RuntimeException(fn + " arg[" + idx + "] cell[" + r + "][" + c + "] must be number or null");
                }
            }
        }

        return new Matrix(data);
    }

    private static Vector requireVector(ElaraScript.Value v, String fn, int idx) {
        if (v.getType() != ElaraScript.Value.Type.ARRAY) {
            throw new RuntimeException(fn + " arg[" + idx + "] must be an array");
        }
        List<ElaraScript.Value> a = v.asArray();
        for (int i = 0; i < a.size(); i++) {
            ElaraScript.Value item = a.get(i);
            if (item.getType() == ElaraScript.Value.Type.NULL) continue;
            if (item.getType() != ElaraScript.Value.Type.NUMBER) {
                throw new RuntimeException(fn + " arg[" + idx + "] element[" + i + "] must be number or null");
            }
        }
        return new Vector(a);
    }

    private static void requireSameShape(Matrix a, Matrix b, String fn) {
        if (a.rows != b.rows || a.cols != b.cols) {
            throw new RuntimeException(fn + ": shape mismatch (" + a.rows + "x" + a.cols + ") vs (" + b.rows + "x" + b.cols + ")");
        }
    }

    private static Double asDoubleOrNull(ElaraScript.Value v) {
        if (v == null) return null;
        if (v.getType() == ElaraScript.Value.Type.NULL) return null;
        if (v.getType() != ElaraScript.Value.Type.NUMBER) return null;
        return v.asNumber();
    }
}
