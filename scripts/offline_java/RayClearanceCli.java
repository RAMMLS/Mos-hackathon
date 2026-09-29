package ru.moshackathon.heatnetwork.solver;

import java.io.BufferedInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;

/** Dependency-free verification harness; not the Spring application. */
public final class RayClearanceCli {
    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        Scanner in = new Scanner(new BufferedInputStream(System.in)).useLocale(Locale.ROOT);
        int cases = in.nextInt();
        for (int k = 0; k < cases; k++) {
            String id = in.next();
            double ox = in.nextDouble(), oy = in.nextDouble();
            double ux = in.nextDouble(), uy = in.nextDouble();
            double from = in.nextDouble(), to = in.nextDouble(), r = in.nextDouble();
            int count = in.nextInt();
            List<double[]> segments = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                segments.add(new double[]{in.nextDouble(), in.nextDouble(),
                        in.nextDouble(), in.nextDouble()});
            }
            List<RayClearanceIntervals.Interval> free = RayClearanceIntervals.free(
                    ox, oy, ux, uy, from, to, r, segments);
            System.out.print(id + " " + free.size());
            for (RayClearanceIntervals.Interval span : free) {
                System.out.printf(Locale.ROOT, " %.12f %.12f", span.from, span.to);
            }
            System.out.println();
        }
    }
}
