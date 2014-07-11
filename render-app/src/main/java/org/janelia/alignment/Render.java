/**
 * License: GPL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.janelia.alignment;

import ij.ImagePlus;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.ImageProcessor;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.util.List;
import java.util.Map;

import mpicbg.models.AffineModel2D;
import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformList;
import mpicbg.models.CoordinateTransformMesh;
import mpicbg.models.TransformMesh;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Render a set of image tile as an ARGB image.
 * 
 * <pre>
 * Usage: java [-options] -cp render.jar org.janelia.alignment.RenderTile [options]
 * Options:
 *       --height
 *      Target image height
 *      Default: 256
 *       --help
 *      Display this note
 *      Default: false
 * *     --res
 *      Mesh resolution, specified by the desired size of a triangle in pixels
 *       --in
 *      Path to the input image if any
 *       --out
 *      Path to the output image
 *       --threads
 *      Number of threads to be used
 *      Default: number of available CPUs
 * *     --url
 *      URL to JSON tile spec
 *       --width
 *      Target image width
 *      Default: 256
 * *     --x
 *      Target image left coordinate
 *      Default: 0
 * *     --y
 *      Target image top coordinate
 *      Default: 0
 * </pre>
 * <p>E.g.:</p>
 * <pre>java -cp render.jar org.janelia.alignment.RenderTile \
 *   --url "file://absolute/path/to/tile-spec.json" \
 *   --out "/absolute/path/to/output.png" \
 *   --x 16536
 *   --y 32
 *   --width 1024
 *   --height 1024
 *   --res 64</pre>
 * 
 * @author Stephan Saalfeld <saalfelds@janelia.hhmi.org>
 */
public class Render
{

    private static final Logger LOG = LoggerFactory.getLogger(Render.class);

	private Render() {}
	
	/**
	 * Create a {@link BufferedImage} from an existing pixel array.  Make sure
	 * that pixels.length == width * height.
	 */
//	final static public BufferedImage createARGBImage( final int[] pixels, final int width, final int height )
//	{
//		assert( pixels.length == width * height ) : "The number of pixels is not equal to width * height.";
//
//		final BufferedImage image = new BufferedImage( width, height, BufferedImage.TYPE_INT_ARGB );
//		final WritableRaster raster = image.getRaster();
//		raster.setDataElements( 0, 0, width, height, pixels );
//		return image;
//	}
	
	
//	final static void saveImage( final BufferedImage image, final String path, final String format ) throws IOException
//	{
//		ImageIO.write( image, format, new File( path ) );
//	}
	
    public static void render(final List<TileSpec> tileSpecs,
                              final BufferedImage targetImage,
                              final double x,
                              final double y,
                              final double triangleSize,
                              final double scale,
                              final boolean areaOffset ) throws IllegalArgumentException {

        final Graphics2D targetGraphics = targetImage.createGraphics();

        LOG.debug("render: entry, processing {} tile specifications", tileSpecs.size());

        long tileLoopStart = System.currentTimeMillis();
        int tileSpecIndex = 0;
        long tileSpecStart;
        long loadMipStop;
        long scaleMipStop;
        long loadMaskStop;
        long mapInterpolatedStop;
        long drawImageStop;

		for ( final TileSpec ts : tileSpecs )
		{
            tileSpecStart = System.currentTimeMillis();

			/* assemble coordinate transformations and add bounding box offset */
			final CoordinateTransformList< CoordinateTransform > ctl = ts.createTransformList();
			final AffineModel2D scaleAndOffset = new AffineModel2D();
			if ( areaOffset )
			{
				final double offset = ( 1 - scale ) * 0.5;
				scaleAndOffset.set( ( float )scale, 0, 0, ( float )scale, -( float )( x * scale + offset ), -( float )( y * scale + offset ) );
			}
			else
				scaleAndOffset.set( ( float )scale, 0, 0, ( float )scale, -( float )( x * scale ), -( float )( y * scale ) );
			
			ctl.add( scaleAndOffset );

            Map.Entry<Integer, ImageAndMask> mipmapEntry;
            ImageAndMask imageAndMask = null;
			ImageProcessor ip = null;
            int width = ts.getWidth();
            int height = ts.getHeight();
			/* figure width and height */
            if ((width < 0) || (height < 0)) {
                mipmapEntry = ts.getFirstMipMapEntry();
                imageAndMask = mipmapEntry.getValue();
				/* load image TODO use Bioformats for strange formats */
                final String imgUrl = imageAndMask.getImageUrl();
				final ImagePlus imp = Utils.openImagePlusUrl( imgUrl );
				if ( imp == null )
				{
                    throw new IllegalArgumentException("failed to load mipmap level " + mipmapEntry.getKey() +
                                                       " image '" + imgUrl + "'");
				}
				ip = imp.getProcessor();
				width = imp.getWidth();
				height = imp.getHeight();
			}

            loadMipStop = System.currentTimeMillis();

			/* estimate average scale */
			final double s = Utils.sampleAverageScale( ctl, width, height, triangleSize );
			int mipmapLevel = Utils.bestMipmapLevel( s );
			
			final ImageProcessor ipMipmap;
			if ( ip == null )
			{
				/* load image TODO use Bioformats for strange formats */
                mipmapEntry = ts.getFloorMipMapEntry(mipmapLevel);
                imageAndMask = mipmapEntry.getValue();
                final String imgUrl = imageAndMask.getImageUrl();
				final ImagePlus imp = Utils.openImagePlusUrl( imgUrl );
				if ( imp == null )
				{
                    throw new IllegalArgumentException("failed to load mipmap level " + mipmapEntry.getKey() +
                                                       " image '" + imgUrl + "' for scaling");
				}
				ip = imp.getProcessor();
                final int currentMipmapLevel = mipmapEntry.getKey();
				if ( currentMipmapLevel >= mipmapLevel )
				{
					mipmapLevel = currentMipmapLevel;
					ipMipmap = ip;
				}
				else
					ipMipmap = Downsampler.downsampleImageProcessor( ip, mipmapLevel - currentMipmapLevel );
			}
			else
			{
				/* create according mipmap level */
				ipMipmap = Downsampler.downsampleImageProcessor( ip, mipmapLevel );
			}

			/* create a target */
			final ImageProcessor tp = ipMipmap.createProcessor( targetImage.getWidth(), targetImage.getHeight() );

            scaleMipStop = System.currentTimeMillis();

			/* open mask */
			final ByteProcessor bpMaskSource;
			final ByteProcessor bpMaskTarget;
			final String maskUrl = imageAndMask.getMaskUrl();
			if ( maskUrl != null )
			{
				final ImagePlus impMask = Utils.openImagePlusUrl( maskUrl );
				if ( impMask == null )
				{
                    LOG.error("Failed to load mask '" + maskUrl + "'.");
					bpMaskSource = null;
					bpMaskTarget = null;
				}
				else
				{
					/* create according mipmap level */
					bpMaskSource = Downsampler.downsampleByteProcessor( impMask.getProcessor().convertToByteProcessor(), mipmapLevel );
					bpMaskTarget = new ByteProcessor( tp.getWidth(), tp.getHeight() );
				}
			}
			else
			{
				bpMaskSource = null;
				bpMaskTarget = null;
			}

            loadMaskStop = System.currentTimeMillis();

			/* attach mipmap transformation */
			final CoordinateTransformList< CoordinateTransform > ctlMipmap = new CoordinateTransformList< CoordinateTransform >();
			ctlMipmap.add( Utils.createScaleLevelTransform( mipmapLevel ) );
			ctlMipmap.add( ctl );
			
			/* create mesh */
			final CoordinateTransformMesh mesh = new CoordinateTransformMesh( ctlMipmap,  ( int )( width / triangleSize + 0.5 ), ipMipmap.getWidth(), ipMipmap.getHeight() );
			
			final ImageProcessorWithMasks source = new ImageProcessorWithMasks( ipMipmap, bpMaskSource, null );
			final ImageProcessorWithMasks target = new ImageProcessorWithMasks( tp, bpMaskTarget, null );
			final TransformMeshMappingWithMasks< TransformMesh > mapping = new TransformMeshMappingWithMasks< TransformMesh >( mesh );
			mapping.mapInterpolated( source, target );

            mapInterpolatedStop = System.currentTimeMillis();

			/* convert to 24bit RGB */
            tp.setMinAndMax(ts.getMinIntensity(), ts.getMaxIntensity());
			final ColorProcessor cp = tp.convertToColorProcessor();
			
			final int[] cpPixels = ( int[] )cp.getPixels();
			final byte[] alphaPixels;
			
			
			/* set alpha channel */
			if ( bpMaskTarget != null )
				alphaPixels = ( byte[] )bpMaskTarget.getPixels();
			else
				alphaPixels = ( byte[] )target.outside.getPixels();

			for ( int i = 0; i < cpPixels.length; ++i )
				cpPixels[ i ] &= 0x00ffffff | ( alphaPixels[ i ] << 24 );

			final BufferedImage image = new BufferedImage( cp.getWidth(), cp.getHeight(), BufferedImage.TYPE_INT_ARGB );
			final WritableRaster raster = image.getRaster();
			raster.setDataElements( 0, 0, cp.getWidth(), cp.getHeight(), cpPixels );
			
			targetGraphics.drawImage( image, 0, 0, null );

            drawImageStop = System.currentTimeMillis();

            LOG.debug("render: tile {} took {} milliseconds to process (load mip:{}, scale mip:{}, load/scale mask:{}, map interpolated:{}, draw image:{})",
                      tileSpecIndex,
                      drawImageStop - tileSpecStart,
                      loadMipStop - tileSpecStart,
                      scaleMipStop - loadMipStop,
                      loadMaskStop - scaleMipStop,
                      mapInterpolatedStop - loadMaskStop,
                      drawImageStop - mapInterpolatedStop);

            tileSpecIndex++;
        }

        LOG.debug("render: exit, {} tiles processed in {} milliseconds",
                  tileSpecs.size(),
                  System.currentTimeMillis() - tileLoopStart);
    }

    public static void render(final List<TileSpec> tileSpecs,
                              final BufferedImage targetImage,
                              final double x,
                              final double y,
                              final double triangleSize)
            throws IllegalArgumentException {
        render(tileSpecs, targetImage, x, y, triangleSize, 1.0, false);
    }

    public static BufferedImage render(final List<TileSpec> tileSpecs,
                                       final double x,
                                       final double y,
                                       final int width,
                                       final int height,
                                       final double triangleSize,
                                       final double scale,
                                       final boolean areaOffset)
            throws IllegalArgumentException {
        final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        render(tileSpecs, image, x, y, triangleSize, scale, areaOffset);
        return image;
    }

    public static BufferedImage render(final List<TileSpec> tileSpecs,
                                       final double x,
                                       final double y,
                                       final int width,
                                       final int height,
                                       final double triangleSize)
            throws IllegalArgumentException {
        return render(tileSpecs, x, y, width, height, triangleSize, 1.0, false);
    }

    public static void main( final String[] args )
	{
		//new ImageJ();

        try {

            final long mainStart = System.currentTimeMillis();
            long parseStop = mainStart;
            long targetOpenStop = mainStart;
            long saveStart = mainStart;
            long saveStop = mainStart;

            final RenderParameters params = RenderParameters.parse(args);

            if (params.displayHelp()) {

                params.showUsage();

            } else {

                LOG.info("main: entry, params={}", params);

                params.validate();

                parseStop = System.currentTimeMillis();

                BufferedImage targetImage = params.openTargetImage();

                targetOpenStop = System.currentTimeMillis();

                render(params.getTileSpecs(),
                       targetImage,
                       params.getX(),
                       params.getY(),
                       params.getRes(),
                       params.getScale(),
                       params.isAreaOffset());

//                final BufferedImage downSampledImage = render(params.getTileSpecs(),
//                                                              params.getX(),
//                                                              params.getY(),
//                                                              (int) (params.getWidth() / params.getScale()),
//                                                              (int) (params.getHeight() / params.getScale()),
//                                                              params.getRes(),
//                                                              1.0,
//                                                              false);
//                ColorProcessor cp = new ColorProcessor(downSampledImage);
//                cp = Downsampler.downsampleColorProcessor(cp, params.getMipmapLevel());
//                new ImagePlus("downsampled", cp).show();
//                new ImagePlus("result", new ColorProcessor(targetImage)).show();

                saveStart = System.currentTimeMillis();

                /* save the modified image */
                final String outputPathOrUri = params.getOut();
                final String outputFormat = outputPathOrUri.substring(outputPathOrUri.lastIndexOf('.') + 1);
                Utils.saveImage(targetImage, outputPathOrUri, outputFormat, params.getQuality());

                saveStop = System.currentTimeMillis();
            }

            LOG.debug("main: processing took {} milliseconds (parse command:{}, open target:{}, render tiles:{}, save target:{})",
                      saveStop - mainStart,
                      parseStop - mainStart,
                      targetOpenStop - parseStop,
                      saveStart - targetOpenStop,
                      saveStop - saveStart);

        } catch (Throwable t) {
            LOG.error("main: caught exception", t);
        }

		//new ImagePlus( params.out ).show();
	}
}