package org.janelia.render.service.util;

import com.mongodb.MongoClient;
import org.janelia.render.service.dao.RenderDao;
import org.janelia.render.service.dao.SharedMongoClient;
import org.janelia.render.service.model.IllegalServiceArgumentException;
import org.janelia.render.service.model.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.UnknownHostException;

/**
 * Shared utility methods for all Render services.
 *
 * @author Eric Trautman
 */
public class RenderServiceUtil {

    public static RenderDao buildDao()
            throws UnknownHostException {
        final MongoClient mongoClient = SharedMongoClient.getInstance();
        return new RenderDao(mongoClient);
    }

    public static void throwServiceException(Throwable t)
            throws ServiceException {

        LOG.error("service failure", t);

        if (t instanceof ServiceException) {
            throw (ServiceException) t;
        } else if (t instanceof IllegalArgumentException) {
            throw new IllegalServiceArgumentException(t.getMessage(), t);
        } else {
            throw new ServiceException(t.getMessage(), t);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(RenderServiceUtil.class);

}