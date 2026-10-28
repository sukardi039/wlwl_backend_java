package com.wlwl.backend.images;

import com.wlwl.backend.rpc.ModuleHandler;
import org.springframework.stereotype.Component;

/**
 * Port of <b>images/</b> reached as an RPC module ({@code /backend/images/}), i.e. the
 * path through images/index.php -> etc/restdx.inc -> post.php.
 *
 * <p>It exists to keep the 404-vs-200 contract honest, and it deliberately implements
 * <b>nothing</b>. Both of its possible paths end in an empty 200:
 *
 * <ul>
 *   <li>restdx.inc authenticates by reading a JSON body. A multipart upload has no JSON
 *       body, so {@code json_decode} fails, {@code gzdecode} fails, {@code $data} is
 *       null, {@code where restkey = ''} matches nothing, and the switch is never
 *       reached. {@link com.wlwl.backend.rpc.BackendGateway} reproduces that: the
 *       envelope parses to null and the response is an empty 200.</li>
 *   <li>Even with a valid body, post.php wraps everything in
 *       {@code if (is_uploaded_file($_FILES['upFile']['tmp_name']))}, and a JSON request
 *       carries no uploaded file, so it emits nothing.</li>
 * </ul>
 *
 * <p>In other words images/post.php is unreachable in the PHP too. It is NOT ported
 * (its body would be dead code written to satisfy a checker), but the module is
 * registered so that {@code /backend/images/} answers an empty 200 like production
 * rather than a 404. The live endpoint is
 * {@link ImageUploadController} at {@code /backend/images/upload.php}, which the Vue
 * client calls directly.
 */
@Component
public class ImagesModule implements ModuleHandler {

    @Override
    public String module() {
        return "images";
    }
}
