package course;

import com.mongodb.MongoClient;
import com.mongodb.MongoClientURI;
import com.mongodb.client.MongoDatabase;
import freemarker.template.Configuration;
import freemarker.template.SimpleHash;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import org.apache.commons.lang3.StringEscapeUtils;
import org.bson.Document;
import spark.Request;
import spark.Response;
import spark.Route;

import javax.servlet.http.Cookie;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static spark.Spark.get;
import static spark.Spark.post;
import static spark.Spark.setPort;

/**
 * BlogController class encapsulates the controllers for the blog web application.
 * It delegates all interaction with MongoDB to three Data Access Objects (DAOs).
 * 
 * Entry point into the web application.
 */

/**
 * @author nramanathan
 * 
 */
public class BlogController {
	private final Configuration configuration;
	private final BlogPostDAO blogPostDao;
	private final UserDAO userDao;
	private final SessionDAO sessionDao;

	public static void main(String[] args) throws IOException {
		if (args.length == 0) {
			new BlogController("mongodb://localhost");
		} else {
			new BlogController(args[0]);
		}
	}

	public BlogController(String mongoURIString) throws IOException {
		final MongoClient mongoClient = new MongoClient(new MongoClientURI(
				mongoURIString));
		final MongoDatabase blogDatabase = mongoClient
				.getDatabase("blog_final");

		blogPostDao = new BlogPostDAO(blogDatabase);
		userDao = new UserDAO(blogDatabase);
		sessionDao = new SessionDAO(blogDatabase);

		configuration = createFreemarkerConfiguration();
		setPort(8082);
		initializeRoutes();
	}

	abstract class FreemarkerBasedRoute extends Route {
		final Template template;

		/**
		 * Constructor
		 * 
		 * @param path
		 *            The route path which is used for matching. (e.g. /hello,
		 *            users/:name)
		 */
		protected FreemarkerBasedRoute(final String path,
				final String templateName) throws IOException {
			super(path);
			template = configuration.getTemplate(templateName);
		}

		@Override
		public Object handle(Request request, Response response) {
			StringWriter writer = new StringWriter();
			try {
				doHandle(request, response, writer);
			} catch (Exception e) {
				e.printStackTrace();
				response.redirect("/internal_error");
			}
			return writer;
		}

		protected abstract void doHandle(final Request request,
				final Response response, final Writer writer)
				throws IOException, TemplateException;

	}

	private void initializeRoutes() throws IOException {
		// This API renders the Blog Home Page
		get(new FreemarkerBasedRoute("/", "blog_template.ftl") {
			@Override
			public void doHandle(Request request, Response response,
					Writer writer) throws IOException, TemplateException {
				String username = sessionDao
						.findUserNameBySessionId(getSessionCookie(request));

				List<Document> posts = blogPostDao.findByDateDescending(10);
				SimpleHash root = new SimpleHash();

				root.put("myposts", posts);
				if (username != null) {
					root.put("username", username);
				}

				template.process(root, writer);
			}
		});

		// This API is used to display actual blog post detail page
		get(new FreemarkerBasedRoute("/post/:permalink", "entry_template.ftl") {
			@Override
			protected void doHandle(Request request, Response response,
					Writer writer) throws IOException, TemplateException {
				String permalink = request.params(":permalink");

				System.out.println("/post: get " + permalink);

				Document post = blogPostDao.findPostByPermalink(permalink);
				if (post == null) {
					response.redirect("/post_not_found");
				} else {
					// empty comment to hold new comment in form at bottom of
					// blog entry detail page
					SimpleHash newComment = new SimpleHash();
					newComment.put("name", "");
					newComment.put("email", "");
					newComment.put("body", "");

					SimpleHash root = new SimpleHash();

					root.put("post", post);
					root.put("comment", newComment);

					template.process(root, writer);
				}
			}
		});

		// This API is used for User Sign up
		post(new FreemarkerBasedRoute("/signup", "signup.ftl") {
			@Override
			protected void doHandle(Request request, Response response,
					Writer writer) throws IOException, TemplateException {
				String email = request.queryParams("email");
				String username = request.queryParams("username");
				String password = request.queryParams("password");
				String verify = request.queryParams("verify");

				HashMap<String, String> root = new HashMap<String, String>();
				root.put("username", StringEscapeUtils.escapeHtml4(username));
				root.put("email", StringEscapeUtils.escapeHtml4(email));

				if (validateSignup(username, password, verify, email, root)) {
					// good user
					System.out.println("Signup: Creating user with: "
							+ username + " " + password);
					if (!userDao.addUser(username, password, email)) {
						// duplicate user
						root.put("username_error",
								"Username already in use, Please choose another");
						template.process(root, writer);
					} else {
						// good user, let's start a session
						String sessionID = sessionDao.startSession(username);
						System.out.println("Session ID is" + sessionID);

						response.raw().addCookie(
								new Cookie("session", sessionID));
						response.redirect("/welcome");
					}
				} else {
					// bad signup
					System.out.println("User Registration did not validate");
					template.process(root, writer);
				}
			}
		});

		// This API is user to render the signup form for blog
		get(new FreemarkerBasedRoute("/signup", "signup.ftl") {
			@Override
			protected void doHandle(Request request, Response response,
					Writer writer) throws IOException, TemplateException {

				SimpleHash root = new SimpleHash();

				// initialize values for the form.
				root.put("username", "");
				root.put("password", "");
				root.put("email", "");
				root.put("password_error", "");
				root.put("username_error", "");
				root.put("email_error", "");
				root.put("verify_error", "");

				template.process(root, writer);
			}
		});

		// This API will present the form which is used to create and process
		// new blog posts
		get(new FreemarkerBasedRoute("/newpost", "newpost_template.ftl") {
			@Override
			protected void doHandle(Request request, Response response,
					Writer writer) throws IOException, TemplateException {

				// get cookie
				String username = sessionDao
						.findUserNameBySessionId(getSessionCookie(request));

				if (username == null) {
					// looks like a bad request. user is not logged in
					response.redirect("/login");
				} else {
					SimpleHash root = new SimpleHash();
					root.put("username", username);

					template.process(root, writer);
				}
			}
		});

		// This API is used to handle the new post submission
		post(new FreemarkerBasedRoute("/newpost", "newpost_template.ftl") {
			@Override
			protected void doHandle(Request request, Response response,
					Writer writer) throws IOException, TemplateException {

				String title = StringEscapeUtils.escapeHtml4(request
						.queryParams("subject"));
				String post = StringEscapeUtils.escapeHtml4(request
						.queryParams("body"));
				String tags = StringEscapeUtils.escapeHtml4(request
						.queryParams("tags"));

				String username = sessionDao
						.findUserNameBySessionId(getSessionCookie(request));

				if (username == null) {
					response.redirect("/login"); // only logged in users can
													// post to blog
				} else if (title.equals("") || post.equals("")) {
					// redisplay page with errors
					HashMap<String, String> root = new HashMap<String, String>();
					root.put("errors",
							"post must contain a title and blog entry.");
					root.put("subject", title);
					root.put("username", username);
					root.put("tags", tags);
					root.put("body", post);
					template.process(root, writer);
				} else {
					// extract tags
					ArrayList<String> tagsArray = extractTags(tags);

					// substitute some <p> for the paragraph breaks
					post = post.replaceAll("\\r?\\n", "<p>");

					String permalink = blogPostDao.addPost(title, post,
							tagsArray, username);

					// now redirect to the blog permalink
					response.redirect("/post/" + permalink);
				}
			}
		});

		get(new FreemarkerBasedRoute("/welcome", "welcome.ftl") {
			@Override
			protected void doHandle(Request request, Response response,
					Writer writer) throws IOException, TemplateException {

				String cookie = getSessionCookie(request);
				String username = sessionDao.findUserNameBySessionId(cookie);

				if (username == null) {
					System.out
							.println("welcome() can't identify the user, redirecting to signup");
					response.redirect("/signup");

				} else {
					SimpleHash root = new SimpleHash();

					root.put("username", username);

					template.process(root, writer);
				}
			}
		});

		// This API is used to process and store a new comment to a particular
		// Blog Post
		post(new FreemarkerBasedRoute("/newcomment", "entry_template.ftl") {
			@Override
			protected void doHandle(Request request, Response response,
					Writer writer) throws IOException, TemplateException {
				String name = StringEscapeUtils.escapeHtml4(request
						.queryParams("commentName"));
				String email = StringEscapeUtils.escapeHtml4(request
						.queryParams("commentEmail"));
				String body = StringEscapeUtils.escapeHtml4(request
						.queryParams("commentBody"));
				String permalink = request.queryParams("permalink");

				Document post = blogPostDao.findPostByPermalink(permalink);
				if (post == null) {
					response.redirect("/post_not_found");
				}
				// check that comment is good
				else if (name.equals("") || body.equals("")) {
					// bounce this back to the user for correction
					SimpleHash root = new SimpleHash();
					SimpleHash comment = new SimpleHash();

					comment.put("name", name);
					comment.put("email", email);
					comment.put("body", body);
					root.put("comment", comment);
					root.put("post", post);
					root.put("errors",
							"Post must contain your name and an actual comment");

					template.process(root, writer);
				} else {
					blogPostDao.addPostComment(name, email, body, permalink);
					response.redirect("/post/" + permalink);
				}
			}
		});

		// This API is used to render the login page to the user
		get(new FreemarkerBasedRoute("/login", "login.ftl") {
			@Override
			protected void doHandle(Request request, Response response,
					Writer writer) throws IOException, TemplateException {
				SimpleHash root = new SimpleHash();

				root.put("username", "");
				root.put("login_error", "");

				template.process(root, writer);
			}
		});

		/*
		 * This API is used to process user login. 
		 * On Success: The API redirects to the welcome page 
		 * On Failure: The API returns an error and provides the user an option to try again
		 */
		post(new FreemarkerBasedRoute("/login", "login.ftl") {
			@Override
			protected void doHandle(Request request, Response response,
					Writer writer) throws IOException, TemplateException {

				String username = request.queryParams("username");
				String password = request.queryParams("password");

				System.out.println("Login: User submitted: " + username + "  "
						+ password);

				Document user = userDao.validateLogin(username, password);

				if (user != null) {

					// valid user, let's log them in
					String sessionID = sessionDao.startSession(user.get("_id")
							.toString());

					if (sessionID == null) {
						response.redirect("/internal_error");
					} else {
						// set the cookie for the user's browser
						response.raw().addCookie(
								new Cookie("session", sessionID));

						response.redirect("/welcome");
					}
				} else {
					SimpleHash root = new SimpleHash();

					root.put("username",
							StringEscapeUtils.escapeHtml4(username));
					root.put("password", "");
					root.put("login_error", "Invalid Login");
					template.process(root, writer);
				}
			}
		});

		// This API retrieves the list of posts that are filed under a particular tag
		get(new FreemarkerBasedRoute("/tag/:thetag", "blog_template.ftl") {
			@Override
			protected void doHandle(Request request, Response response,
					Writer writer) throws IOException, TemplateException {

				String username = sessionDao
						.findUserNameBySessionId(getSessionCookie(request));
				SimpleHash root = new SimpleHash();

				String tag = StringEscapeUtils.escapeHtml4(request
						.params(":thetag"));
				List<Document> posts = blogPostDao.findByTagDateDescending(tag);

				root.put("myposts", posts);
				if (username != null) {
					root.put("username", username);
				}

				template.process(root, writer);
			}
		});

		// This API allows the user to like a particular comment of a particular blog post
		post(new FreemarkerBasedRoute("/like", "entry_template.ftl") {
			@Override
			protected void doHandle(Request request, Response response,
					Writer writer) throws IOException, TemplateException {

				String permalink = request.queryParams("permalink");
				String commentOrdinalStr = request
						.queryParams("comment_ordinal");

				// look up the post in question

				int ordinal = Integer.parseInt(commentOrdinalStr);

				// TODO: check return or have checkSession throw
				String username = sessionDao
						.findUserNameBySessionId(getSessionCookie(request));
				Document post = blogPostDao.findPostByPermalink(permalink);

				// if post not found, redirect to post not found error
				if (post == null) {
					response.redirect("/post_not_found");
				} else {
					blogPostDao.likePost(permalink, ordinal);

					response.redirect("/post/" + permalink);
				}
			}
		});

		// API that is used to tell the user that the URL is not found
		get(new FreemarkerBasedRoute("/post_not_found", "post_not_found.ftl") {
			@Override
			protected void doHandle(Request request, Response response,
					Writer writer) throws IOException, TemplateException {
				SimpleHash root = new SimpleHash();
				template.process(root, writer);
			}
		});

		// This API is used to logout of the Blog Application
		get(new FreemarkerBasedRoute("/logout", "signup.ftl") {
			@Override
			protected void doHandle(Request request, Response response,
					Writer writer) throws IOException, TemplateException {

				String sessionID = getSessionCookie(request);

				if (sessionID == null) {
					// no session to end
					response.redirect("/login");
				} else {
					// deletes from session table
					sessionDao.endSession(sessionID);

					// this should delete the cookie
					Cookie c = getSessionCookieActual(request);
					c.setMaxAge(0);

					response.raw().addCookie(c);

					response.redirect("/login");
				}
			}
		});

		// Generic API that is used to process internal errors
		get(new FreemarkerBasedRoute("/internal_error", "error_template.ftl") {
			@Override
			protected void doHandle(Request request, Response response,
					Writer writer) throws IOException, TemplateException {
				SimpleHash root = new SimpleHash();

				root.put("error", "System has encountered an error.");
				template.process(root, writer);
			}
		});
	}

	/**
	 * @param request
	 * helper function to get session cookie as string
	 * 
	 * @return Cookie in String format
	 */
	private String getSessionCookie(final Request request) {
		if (request.raw().getCookies() == null) {
			return null;
		}
		for (Cookie cookie : request.raw().getCookies()) {
			if (cookie.getName().equals("session")) {
				return cookie.getValue();
			}
		}
		return null;
	}

	/**
	 * @param request
	 * helper function to get session cookie as string
	 * 
	 * @return Cookie in String format
	 */
	private Cookie getSessionCookieActual(final Request request) {
		if (request.raw().getCookies() == null) {
			return null;
		}
		for (Cookie cookie : request.raw().getCookies()) {
			if (cookie.getName().equals("session")) {
				return cookie;
			}
		}
		return null;
	}

	/**
	 * @param tags
	 * Parse the string object and obtains the tags and form an arraylist of tags
	 * 
	 * @return ArrayList of Tags
	 */
	private ArrayList<String> extractTags(String tags) {
		tags = tags.replaceAll("\\s", "");
		String tagArray[] = tags.split(",");

		// let's clean it up, removing the empty string and removing dups
		ArrayList<String> cleaned = new ArrayList<String>();
		for (String tag : tagArray) {
			if (!tag.equals("") && !cleaned.contains(tag)) {
				cleaned.add(tag);
			}
		}

		return cleaned;
	}

	/**
	 * @param username
	 * @param password
	 * @param verify
	 * @param email
	 * @param errors
	 * Validates the registration form for sign up
	 * 
	 * @return Status of the validation
	 */
	public boolean validateSignup(String username, String password,
			String verify, String email, HashMap<String, String> errors) {
		String USER_RE = "^[a-zA-Z0-9_-]{3,20}$";
		String PASS_RE = "^.{3,20}$";
		String EMAIL_RE = "^[\\S]+@[\\S]+\\.[\\S]+$";

		errors.put("username_error", "");
		errors.put("password_error", "");
		errors.put("verify_error", "");
		errors.put("email_error", "");

		if (!username.matches(USER_RE)) {
			errors.put("username_error",
					"invalid username. try just letters and numbers");
			return false;
		}

		if (!password.matches(PASS_RE)) {
			errors.put("password_error", "invalid password.");
			return false;
		}

		if (!password.equals(verify)) {
			errors.put("verify_error", "password must match");
			return false;
		}

		if (!email.equals("")) {
			if (!email.matches(EMAIL_RE)) {
				errors.put("email_error", "Invalid Email Address");
				return false;
			}
		}

		return true;
	}

	private Configuration createFreemarkerConfiguration() {
		Configuration retVal = new Configuration();
		retVal.setClassForTemplateLoading(BlogController.class, "/freemarker");
		return retVal;
	}
}
