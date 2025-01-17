package org.jboss.wildscribe.site;

import com.googlecode.htmlcompressor.compressor.HtmlCompressor;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import org.jboss.dmr.ModelNode;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * class Responsible for generating sites
 *
 * @author Stuart Douglas
 */
public class SiteGenerator {

    public static final String INDEX_HTML = "index.html";
    public static final String ABOUT_HTML = "about.html";
    public static final String RESOURCE_HTML = "resource.html";
    public final String layoutHtml;
    private final File singlePageDmr;
    private final List<Version> versions;
    private final Configuration configuration;
    private final File outputDir;

    public SiteGenerator(List<Version> versions, Configuration configuration, File outputDir) {
        this.versions = versions;
        this.configuration = configuration;
        this.outputDir = outputDir;
        layoutHtml = "layout.html";
        this.singlePageDmr = null;
    }

    public SiteGenerator(File single, Configuration configuration, File outputDir) {
        this.versions = null;
        this.configuration = configuration;
        this.outputDir = outputDir;
        layoutHtml = "single-layout.html";
        this.singlePageDmr = single;
    }

    public void createMainPage() throws IOException, TemplateException {
        Template template = configuration.getTemplate(layoutHtml);
        final Map<String, Object> data = new HashMap<String, Object>();
        data.put("page", INDEX_HTML);
        data.put("versions", versions);
        data.put("urlbase", getUrlBase());
        boolean wf = false;
        boolean eap = false;
        boolean as7 = false;
        for (Version version : versions) {
            if (version.getProduct().equals(Version.JBOSS_AS7) && !as7) {
                as7 = true;
                data.put("as7", version);
            } else if (version.getProduct().equals(Version.JBOSS_EAP) && !eap) {
                eap = true;
                data.put("eap", version);
            } else if (version.getProduct().equals(Version.WILDFLY) && !wf) {
                wf = true;
                data.put("wildfly", version);
            }
        }

        template.process(data, new PrintWriter(new FileOutputStream(new File(outputDir, INDEX_HTML))));
    }

    public void createAboutPage() throws IOException, TemplateException {
        Template template = configuration.getTemplate(layoutHtml);
        final Map<String, Object> data = new HashMap<String, Object>();
        data.put("page", ABOUT_HTML);
        data.put("versions", versions);
        data.put("urlbase", getUrlBase());
        template.process(data, new PrintWriter(new FileOutputStream(new File(outputDir, ABOUT_HTML))));
    }


    public void createVersions() throws IOException, TemplateException {
        for (Version version : versions) {
            System.out.println("Processing " + version.getProduct() + " " + version.getVersion());
            createVersion(version);
        }
    }

    private void createVersion(final Version version) throws IOException, TemplateException {
        final ModelNode model = new ModelNode();
        model.readExternal(new FileInputStream(version.getDmrFile()));


        Template template = configuration.getTemplate(layoutHtml);

        createResourcePage(version, model, template, false);

    }

    public void createSingleVersion() throws IOException, TemplateException {
        final ModelNode model = new ModelNode();
        model.readExternal(new FileInputStream(singlePageDmr));
        Template template = configuration.getTemplate(layoutHtml);
        createResourcePage(new Version(System.getProperty("name"), "", singlePageDmr), model,template, true);

    }


    private void createResourcePage(Version version, ModelNode model, Template template, boolean single, PathElement... path) throws TemplateException, IOException {
        final String currentUrl = buildCurrentUrl(path);
        final Map<String, Object> data = new HashMap<String, Object>();
        data.put("page", RESOURCE_HTML);
        data.put("versions", versions);
        data.put("version", version);
        data.put("urlbase", getUrlBase());
        data.put("currenturl", currentUrl);
        ResourceDescription resourceDescription = ResourceDescription.fromModelNode(model);
        data.put("model", resourceDescription);

        final List<Breadcrumb> crumbs = buildBreadcrumbs(version, path);
        data.put("breadcrumbs", crumbs);

        File parent;
        if(single) {
            parent = new File(outputDir.getAbsolutePath() + File.separator + currentUrl);
        } else {
            parent = new File(outputDir.getAbsolutePath() + File.separator + version.getProduct() + File.separator + version.getVersion() + currentUrl);
        }
        parent.mkdirs();
        StringWriter stringWriter = new StringWriter();
        template.process(data, stringWriter);
        HtmlCompressor compressor = new HtmlCompressor();
        String compressedHtml = compressor.compress(stringWriter.getBuffer().toString());
        try (FileOutputStream stream = new FileOutputStream(new File(parent, INDEX_HTML))) {
            stream.write(compressedHtml.getBytes("UTF-8"));
        }

        if (resourceDescription.getChildren() != null) {
            for (Child child : resourceDescription.getChildren()) {
                if (child.getChildren().isEmpty()) {
                    PathElement[] newPath = addToPath(path, child.getName(), "*");

                    ModelNode childModel = model.get("children").get(child.getName());
                    if (childModel.hasDefined("model-description")) {
                        ModelNode newModel = childModel.get("model-description").get("*");
                        if(newModel.hasDefined("operations")) { //if this is not defined then something is bogus. If the managment model was 100% this would not be nessesary
                            createResourcePage(version, newModel, template, single, newPath);
                        }
                    }
                } else {
                    for (Child registration : child.getChildren()) {
                        PathElement[] newPath = addToPath(path, child.getName(), registration.getName());

                        ModelNode childModel = model.get("children").get(child.getName());
                        if (childModel.hasDefined("model-description") &&  childModel.get("model-description").hasDefined(registration.getName())) {
                            ModelNode newModel = childModel.get("model-description").get(registration.getName());

                            createResourcePage(version, newModel, template, single, newPath);
                        }
                    }
                }
            }
        }

    }

    private String getUrlBase() {
        if (System.getProperty("url") == null) {
            return outputDir.getAbsolutePath();
        }
        return System.getProperty("url");
    }

    private PathElement[] addToPath(PathElement[] path, final String key, final String value) {
        PathElement[] newPath = new PathElement[path.length + 1];
        System.arraycopy(path, 0, newPath, 0, path.length);
        newPath[path.length] = new PathElement(key, value);
        return newPath;
    }

    private List<Breadcrumb> buildBreadcrumbs(Version version, PathElement[] path) {
        final List<Breadcrumb> crumbs = new ArrayList<Breadcrumb>();
        crumbs.add(new Breadcrumb(version.getProduct() + " " + version.getVersion(), "index.html"));
        StringBuilder currentUrl = new StringBuilder();
        for (PathElement i : path) {
            currentUrl.append("/" + i.getKey());
            if (!i.isWildcard()) {
                currentUrl.append("/" + i.getValue());
            }
            crumbs.add(new Breadcrumb(i.getKey() + (i.isWildcard() ? "" : ("=" + i.getValue())), currentUrl.toString() + "/index.html"));
        }
        return crumbs;
    }

    private String buildCurrentUrl(final PathElement... path) {
        StringBuilder sb = new StringBuilder();
        for (PathElement i : path) {
            sb.append('/');
            sb.append(i.getKey());
            if (!i.isWildcard()) {
                sb.append('/');
                sb.append(i.getValue());
            }
        }
        return sb.toString();
    }
}
