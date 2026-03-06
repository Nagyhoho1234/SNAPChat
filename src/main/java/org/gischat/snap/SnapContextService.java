package org.gischat.snap;

import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductManager;
import org.esa.snap.rcp.SnapApp;

public class SnapContextService {

    public static String getContext() {
        StringBuilder sb = new StringBuilder();

        try {
            ProductManager pm = SnapApp.getDefault().getProductManager();
            Product[] products = pm.getProducts();

            if (products.length == 0) {
                sb.append("No products open.\n");
                return sb.toString();
            }

            sb.append("Open Products:\n");
            for (Product product : products) {
                sb.append("  - \"").append(product.getName()).append("\"\n");
                sb.append("    File: ").append(product.getFileLocation() != null
                        ? product.getFileLocation().getAbsolutePath() : "(in memory)").append("\n");
                sb.append("    Type: ").append(product.getProductType()).append("\n");
                sb.append("    Size: ").append(product.getSceneRasterWidth()).append("x")
                        .append(product.getSceneRasterHeight()).append(" pixels\n");

                var crs = product.getSceneCRS();
                if (crs != null) {
                    sb.append("    CRS: ").append(crs.getName()).append("\n");
                }

                var gc = product.getSceneGeoCoding();
                if (gc != null) {
                    var bounds = product.getSceneGeoCoding().getGeoPos(
                            new org.esa.snap.core.datamodel.PixelPos(0, 0), null);
                    sb.append("    Upper-left: ").append(String.format("%.4f, %.4f", bounds.getLat(), bounds.getLon())).append("\n");
                }

                // List bands (up to 15)
                Band[] bands = product.getBands();
                sb.append("    Bands (").append(bands.length).append("): ");
                int max = Math.min(bands.length, 15);
                for (int i = 0; i < max; i++) {
                    if (i > 0) sb.append(", ");
                    Band b = bands[i];
                    sb.append(b.getName()).append(" (").append(b.getDataType()).append(")");
                }
                if (bands.length > 15) sb.append(", ...");
                sb.append("\n");

                // Start/end time
                if (product.getStartTime() != null) {
                    sb.append("    Start: ").append(product.getStartTime()).append("\n");
                }
                if (product.getEndTime() != null) {
                    sb.append("    End: ").append(product.getEndTime()).append("\n");
                }
            }

            // Selected product
            Product selected = SnapApp.getDefault().getSelectedProduct(SnapApp.SelectionSourceHint.AUTO);
            if (selected != null) {
                sb.append("\nSelected Product: \"").append(selected.getName()).append("\"\n");
            }

        } catch (Exception e) {
            sb.append("Error reading SNAP context: ").append(e.getMessage()).append("\n");
        }

        return sb.toString();
    }
}
