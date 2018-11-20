package org.mapfish.print.map.tiled.wms;

import com.codahale.metrics.MetricRegistry;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.mapfish.print.attribute.map.MapBounds;
import org.mapfish.print.config.Configuration;
import org.mapfish.print.http.MfClientHttpRequestFactory;
import org.mapfish.print.map.geotools.StyleSupplier;
import org.mapfish.print.map.image.wms.WmsUtilities;
import org.mapfish.print.map.tiled.AbstractTiledLayer;
import org.mapfish.print.map.tiled.TileCacheInformation;
import org.opengis.referencing.FactoryException;
import org.springframework.http.client.ClientHttpRequest;

import java.awt.Dimension;
import java.awt.Rectangle;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.ForkJoinPool;
import javax.annotation.Nonnull;

/**
 * Strategy object for rendering WMS based layers .
 */
public final class TiledWmsLayer extends AbstractTiledLayer {
    private final TiledWmsLayerParam param;


    /**
     * Constructor.
     *
     * @param forkJoinPool the thread pool for doing the rendering.
     * @param styleSupplier strategy for loading the style for this layer.
     * @param param the information needed to create WMS requests.
     * @param registry the metrics registry.
     * @param configuration the configuration.
     */
    public TiledWmsLayer(
            @Nonnull final ForkJoinPool forkJoinPool,
            @Nonnull final StyleSupplier<GridCoverage2D> styleSupplier,
            @Nonnull final TiledWmsLayerParam param,
            @Nonnull final MetricRegistry registry,
            @Nonnull final Configuration configuration) {
        super(forkJoinPool, styleSupplier, param, registry, configuration);
        this.param = param;
    }

    /**
     * Get the HTTP params.
     *
     * @return the HTTP params
     */
    public TiledWmsLayerParam getParams() {
        return this.param;
    }

    @Override
    protected TileCacheInformation createTileInformation(
            final MapBounds bounds, final Rectangle paintArea, final double dpi) {
        return new WmsTileCacheInformation(bounds, paintArea, dpi);
    }

    @Override
    public RenderType getRenderType() {
        return RenderType.fromMimeType(this.param.imageFormat);
    }

    private final class WmsTileCacheInformation extends TileCacheInformation {

        private WmsTileCacheInformation(
                final MapBounds bounds, final Rectangle paintArea, final double dpi) {
            super(bounds, paintArea, dpi, TiledWmsLayer.this.param);
        }

        @Nonnull
        @Override
        public ClientHttpRequest getTileRequest(
                final MfClientHttpRequestFactory httpRequestFactory,
                final String commonUrl,
                final ReferencedEnvelope tileBounds,
                final Dimension tileSizeOnScreen,
                final int column,
                final int row)
                throws IOException, URISyntaxException, FactoryException {

            final CroppedStuff croppedStuff = cropOutOfBoundTiles(tileBounds, tileSizeOnScreen);

            final URI uri = WmsUtilities.makeWmsGetLayerRequest(TiledWmsLayer.this.param, new URI(commonUrl),
                                                                croppedStuff.sizeOnScreen, this.dpi, 0.0,
                                                                croppedStuff.tileBounds);
            return WmsUtilities.createWmsRequest(httpRequestFactory, uri, TiledWmsLayer.this.param.method);
        }

        private CroppedStuff cropOutOfBoundTiles(
                final ReferencedEnvelope tileBounds, final Dimension sizeOnScreen) {
            // the way the tiles are build makes that we go out of bounds only on the right and on the top
            final ReferencedEnvelope mapBounds = getTileCacheBounds();
            ReferencedEnvelope croppedTileBounds;
            Dimension croppedSizeOnScreen;
            if (tileBounds.getMaxX() > mapBounds.getMaxX()) {
                final double origWidth = tileBounds.getWidth();
                croppedTileBounds = new ReferencedEnvelope(tileBounds.getMinX(), mapBounds.getMaxX(),
                                                           tileBounds.getMinY(), tileBounds.getMaxY(),
                                                           tileBounds.getCoordinateReferenceSystem());
                croppedSizeOnScreen = new Dimension(
                        (int) Math.round(sizeOnScreen.width * croppedTileBounds.getWidth() / origWidth),
                        sizeOnScreen.height);
            } else {
                croppedTileBounds = tileBounds;
                croppedSizeOnScreen = sizeOnScreen;
            }

            //TODO: could crop the top tiles, but doesn't work with the rest of the code which doesn't
            //      support partial tiles (the right cropping works by mistake)
            /*if (croppedTileBounds.getMaxY() > mapBounds.getMaxY()) {
                final double origHeight = croppedTileBounds.getHeight();
                croppedTileBounds = new ReferencedEnvelope(tileBounds.getMinX(), tileBounds.getMaxX(),
                                                           tileBounds.getMinY(), mapBounds.getMaxY(),
                                                           tileBounds.getCoordinateReferenceSystem());
                croppedTileBounds = croppedTileBounds.intersection(mapBounds);
                croppedSizeOnScreen = new Dimension(
                        croppedSizeOnScreen.width,
                        (int) Math.round(croppedSizeOnScreen.height * croppedTileBounds.getHeight() /
                                                 origHeight));
            }*/

            return new CroppedStuff(croppedTileBounds, croppedSizeOnScreen);
        }

        @Override
        public double getResolution() {
            final ReferencedEnvelope cacheBounds = getTileCacheBounds();
            return cacheBounds.getWidth() / this.paintArea.width;
        }

        @Override
        public Double getLayerDpi() {
            return this.dpi;
        }

        @Override
        public Dimension getTileSize() {
            return TiledWmsLayer.this.param.getTileSize();
        }

        @Nonnull
        @Override
        protected ReferencedEnvelope getTileCacheBounds() {
            return new ReferencedEnvelope(
                    this.bounds.toReferencedEnvelope(paintArea),
                    this.bounds.getProjection());
        }

        /**
         * Just to work around language limitation (cannot return 2 values).
         */
        private final class CroppedStuff {
            final ReferencedEnvelope tileBounds;
            final Dimension sizeOnScreen;

            private CroppedStuff(final ReferencedEnvelope tileBounds, final Dimension sizeOnScreen) {
                this.tileBounds = tileBounds;
                this.sizeOnScreen = sizeOnScreen;
            }
        }
    }
}
