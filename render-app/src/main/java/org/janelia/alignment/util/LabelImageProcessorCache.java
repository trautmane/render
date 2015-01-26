package org.janelia.alignment.util;

import ij.process.ColorProcessor;
import ij.process.ImageProcessor;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This cache overrides the standard load implementation by replacing real tile pixels with pixels
 * of a single label color.  Each tile receives a distinct label color.  This labelling feature was
 * introduced to support processing using Michael Kazhdan's Distributed Gradient-Domain Processing
 * of Planar and Spherical Images approach (see
 *
 * <a href="http://www.cs.jhu.edu/~misha/Code/DMG/Version3.11/">
 *     http://www.cs.jhu.edu/~misha/Code/DMG/Version3.11/
 * </a>).
 *
 * Although the approach does not require it, an attempt is made to randomly distribute assigned label
 * colors so that adjacent tiles are less likely to be assigned similar label colors.
 *
 * @author Eric Trautman
 */
public class LabelImageProcessorCache extends ImageProcessorCache {

    private int width;
    private int height;

    private int labelIndex;
    private List<Color> colors;
    private Map<String, Color> urlToColor;

    /**
     * Constructs a cache instance using the specified parameters.
     *
     * @param  maximumNumberOfCachedPixels         the maximum number of pixels to maintain in the cache.
     *                                             This should roughly correlate to the maximum amount of
     *                                             memory for the cache.
     *
     * @param  recordStats                         if true, useful tuning stats like cache hits and loads will be
     *                                             maintained (presumably at some nominal overhead cost);
     *                                             otherwise stats are not maintained.
     *
     * @param  cacheOriginalsForDownSampledImages  if true, when down sampled images are requested their source
     *                                             images will also be cached (presumably improving the speed
     *                                             of future down sampling to a different level);
     *                                             otherwise only the down sampled result images are cached.
     *
     * @param width                                standard width for all loaded tiles.
     *
     * @param height                               standard height for all loaded tiles.
     *
     * @param maxLabels                            maximum number of distinct label colors (tiles) needed.
     */
    public LabelImageProcessorCache(long maximumNumberOfCachedPixels,
                                    boolean recordStats,
                                    boolean cacheOriginalsForDownSampledImages,
                                    int width,
                                    int height,
                                    int maxLabels) {

        super(maximumNumberOfCachedPixels, recordStats, cacheOriginalsForDownSampledImages);

        this.width = width;
        this.height = height;

        this.labelIndex = -1;
        initColors(maxLabels);
        this.urlToColor = new HashMap<String, Color>((int) (maxLabels * 1.4));
    }

    /**
     * Loads a label image processor when cache misses occur for source images.
     * Masks are loaded in the standard manner.
     *
     * @param  url               url for the image.
     * @param  downSampleLevels  number of levels to further down sample the image.
     * @param  isMask            indicates whether this image is a mask.
     *
     * @return a newly loaded image processor to be cached.
     *
     * @throws IllegalArgumentException
     *   if the image cannot be loaded.
     */
    @Override
    protected ImageProcessor loadImageProcessor(final String url,
                                                final int downSampleLevels,
                                                final boolean isMask)
            throws IllegalArgumentException {

        final ImageProcessor imageProcessor;

        if (isMask) {
            imageProcessor = super.loadImageProcessor(url, downSampleLevels, true);
        } else {

            Color labelColor = urlToColor.get(url);

            if (labelColor == null) {
                final int index = getNextLabelIndex();
                labelColor = colors.get(index);
                urlToColor.put(url, labelColor);
            }

            if (LOG.isDebugEnabled()) {
                LOG.debug("loadImageProcessor: loading label, url={}, downSampleLevels={}, color={}",
                          url, downSampleLevels, labelColor);
            }

            imageProcessor = loadLabelProcessor(labelColor);
        }

        return imageProcessor;
    }

    private ImageProcessor loadLabelProcessor(final Color color)
            throws IllegalArgumentException {

        final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g2d = image.createGraphics();
        g2d.drawImage(image, 0, 0, color, null);
        return new ColorProcessor(image);

    }

    private synchronized int getNextLabelIndex() {
        labelIndex++;
        return labelIndex;
    }

    private void initColors(int maxLabels) {

        if (maxLabels == 0) {
            throw new IllegalArgumentException("max labels must be greater than zero");
        }

        final double cubeRoot = Math.cbrt(maxLabels);
        final int maxValue = 255;
        if (cubeRoot > maxValue) {
            throw new IllegalArgumentException("color model cannot support " + maxLabels + " distinct labels");
        }

        int step = (int) (maxValue / cubeRoot);
        if (step > 1) {
            step = step - 1;
        }

        this.colors = new ArrayList<Color>(maxLabels);

        for (int red = 0; red < maxValue; red += step) {
            for (int green = 0; green < maxValue; green += step) {
                for (int blue = 0; blue < maxValue; blue += step) {
                    if ((red != green) || (red != blue)) { // skip rgb values that look like black background
                        this.colors.add(new Color(red, green, blue));
                    }
                    if (this.colors.size() == maxLabels)  {
                        break;
                    }
                }
            }
        }

        if (this.colors.size() < maxLabels) {
            throw new IllegalStateException("failed to create " + maxLabels + " distinct label colors");
        }

        Collections.shuffle(this.colors);
    }

    private static final Logger LOG = LoggerFactory.getLogger(LabelImageProcessorCache.class);

}