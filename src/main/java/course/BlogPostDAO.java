package course;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Sorts.descending;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.bson.Document;

import com.mongodb.BasicDBObject;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;

/**
 * @author nramanathan
 * 
 */
public class BlogPostDAO {
	private final MongoCollection<Document> postsCollection;

	public BlogPostDAO(final MongoDatabase blogDatabase) {
		postsCollection = blogDatabase.getCollection("posts");
	}

	/**
	 * @param permalink
	 * 
	 * Find a post by using permalink
	 * @return Document of the Post object
	 */
	public Document findPostByPermalink(String permalink) {
		Document post = postsCollection.find(eq("permalink", permalink))
				.first();

		// fix up if a post has no likes
		if (post != null) {
			List<Document> comments = (List<Document>) post.get("comments");
			for (Document comment : comments) {
				if (!comment.containsKey("num_likes")) {
					comment.put("num_likes", 0);
				}
			}
		}
		return post;
	}

	/**
	 * @param limit
	 * Obtain a list of posts sorted by date in descending order limited to a particular number
	 * 
	 * @return list of Post objects
	 */
	public List<Document> findByDateDescending(int limit) {
		return postsCollection.find().sort(descending("date")).limit(limit)
				.into(new ArrayList<Document>());
	}

	/**
	 * @param tag
	 * Obtain a list of posts sorted by date in descending order limited to a particular number
	 * 
	 * @return
	 */
	public List<Document> findByTagDateDescending(final String tag) {
		return postsCollection.find(eq("tags", tag)).sort(descending("date"))
				.limit(10).into(new ArrayList<Document>());
	}

	/**
	 * @param title
	 * @param body
	 * @param tags
	 * @param username
	 * Add a new Post object to the posts collection
	 * 
	 * @return permalink of the post object
	 */
	public String addPost(String title, String body, List tags, String username) {
		String permalink = title.replaceAll("\\s", "_"); // whitespace becomes _
		permalink = permalink.replaceAll("\\W", ""); // get rid of non
														// alphanumeric
		permalink = permalink.toLowerCase();

		Document post = new Document("title", title).append("author", username)
				.append("body", body).append("permalink", permalink)
				.append("tags", tags).append("comments", new ArrayList())
				.append("date", new Date());

		postsCollection.insertOne(post);

		return permalink;
	}

	/**
	 * @param name
	 * @param email
	 * @param body
	 * @param permalink
	 * Add a comment to a particular post uniquely identified by the permalink
	 * 
	 */
	public void addPostComment(final String name, final String email,
			final String body, final String permalink) {
		Document comment = new Document("author", name).append("body", body);

		if (email != null && !email.isEmpty()) {
			comment.append("email", email);
		}

		postsCollection.updateOne(eq("permalink", permalink), new Document(
				"$push", new Document("comments", comment)));
	}

	/**
	 * @param permalink
	 * @param ordinal
	 * Update the likes of a comment on a particular post pointed uniquely by the permalink
	 * 
	 */
	public void likePost(final String permalink, final int ordinal) {
		//
		//
		// XXX Final Question 4 - work here
		// You must increment the number of likes on the comment in position
		// `ordinal`
		// on the post identified by `permalink`.
		//
		//
		postsCollection.updateOne(new Document("permalink", permalink),
				new Document("$inc", new Document("comments." + ordinal
						+ ".num_likes", 1)));
	}
}
