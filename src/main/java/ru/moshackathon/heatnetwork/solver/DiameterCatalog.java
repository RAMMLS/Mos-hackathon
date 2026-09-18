package ru.moshackathon.heatnetwork.solver;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
public class DiameterCatalog {
    private final List<Row> rows = Arrays.asList(
            new Row(50, 3.5, 181, 74023, 96180),
            new Row(65, 8.3, 245, 78631, 109989),
            new Row(80, 13.2, 327, 83530, 117582),
            new Row(100, 22.3, 419, 89748, 133694),
            new Row(125, 40.2, 554, 97275, 148030),
            new Row(150, 65.1, 696, 105507, 152295),
            new Row(200, 152.3, 1042, 120275, 181766),
            new Row(250, 274.9, 1379, 135323, 202030),
            new Row(300, 437.4, 1718, 150022, 228707),
            new Row(400, 943.1, 2477, 190299, 271317),
            new Row(500, 1663.4, 3245, 224137, 333884),
            new Row(600, 2627.7, 4037, 264790, 372703),
            new Row(700, 3735.1, 4775, 324298, 439571),
            new Row(800, 5296.8, 5644, 325996, 489918),
            new Row(900, 7165.0, 6518, 327693, 553607),
            new Row(1000, 9391.8, 7419, 418777, 606679),
            new Row(1200, 15012.8, 9288, 428074, 825692),
            new Row(1400, 22501.9, 11276, 683417, 978584)
    );

    public Row select(double flowTph, double lengthMeters) {
        Row firstByFlow = null;
        for (Row row : rows) {
            if (row.capacityTph >= flowTph) {
                firstByFlow = row;
                break;
            }
        }
        if (firstByFlow == null) {
            throw new IllegalArgumentException("No diameter can carry flow " + flowTph);
        }
        if (lengthMeters <= firstByFlow.maxLengthMeters) {
            return firstByFlow;
        }
        int index = rows.indexOf(firstByFlow);
        if (index + 1 < rows.size()) {
            Row next = rows.get(index + 1);
            if (lengthMeters <= next.maxLengthMeters) {
                return next;
            }
        }
        return firstByFlow;
    }

    public double chamberCost(int diameter) {
        if (diameter <= 200) {
            return 3_000_000;
        }
        if (diameter <= 500) {
            return 5_000_000;
        }
        if (diameter <= 1000) {
            return 8_000_000;
        }
        return 12_000_000;
    }

    public static class Row {
        private final int diameter;
        private final double capacityTph;
        private final double maxLengthMeters;
        private final double newConstructionRubPerMeter;
        private final double reconstructionRubPerMeter;

        Row(int diameter, double capacityTph, double maxLengthMeters,
            double newConstructionRubPerMeter, double reconstructionRubPerMeter) {
            this.diameter = diameter;
            this.capacityTph = capacityTph;
            this.maxLengthMeters = maxLengthMeters;
            this.newConstructionRubPerMeter = newConstructionRubPerMeter;
            this.reconstructionRubPerMeter = reconstructionRubPerMeter;
        }

        public int getDiameter() {
            return diameter;
        }

        public double getNewConstructionRubPerMeter() {
            return newConstructionRubPerMeter;
        }
    }
}
