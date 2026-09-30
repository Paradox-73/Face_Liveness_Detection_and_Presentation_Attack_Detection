package io.mosip.mocksync.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;

/**
 * Operator accounts from {@code data/users.json}. Passwords are stored only as salted SHA-256 hashes; the demo logins are listed in docs/test-data.md.
 */
@Component
public class UserStore {

	private final List<User> users;

	/**
	 * Loads the accounts.
	 *
	 * @param data reference data
	 */
	public UserStore(ReferenceData data) {
		this.users = data.json("users.json", new TypeReference<List<User>>() {
		});
	}

	/**
	 * Finds an account by user ID, ignoring case.
	 *
	 * @param userId user ID
	 * @return account
	 */
	public Optional<User> find(String userId) {
		return userId == null ? Optional.empty()
				: users.stream().filter(u -> u.getUserId().equalsIgnoreCase(userId.trim())).findFirst();
	}

	/**
	 * Returns all accounts.
	 *
	 * @return copy of the list
	 */
	public List<User> all() {
		return new ArrayList<>(users);
	}

	/**
	 * One operator account.
	 */
	public static class User {
		private String userId;
		private String passwordHash;
		private String name;
		private List<String> roles = new ArrayList<>();
		private boolean active = true;

		/**
		 * Returns the user ID.
		 *
		 * @return user ID
		 */
		public String getUserId() {
			return userId;
		}

		/**
		 * Sets the user ID.
		 *
		 * @param userId user ID
		 */
		public void setUserId(String userId) {
			this.userId = userId;
		}

		/**
		 * Returns the stored password hash, {@code sha256:<hex salt>:<hex SHA-256(salt + password)>}.
		 *
		 * @return password hash
		 */
		public String getPasswordHash() {
			return passwordHash;
		}

		/**
		 * Sets the stored password hash.
		 *
		 * @param passwordHash hash in {@code sha256:<salt>:<digest>} form
		 */
		public void setPasswordHash(String passwordHash) {
			this.passwordHash = passwordHash;
		}

		/**
		 * Checks a login password against the stored salted hash in constant time.
		 *
		 * @param candidate password sent by the client
		 * @return true when it matches
		 */
		public boolean passwordMatches(String candidate) {
			if (passwordHash == null || candidate == null) {
				return false;
			}
			String[] parts = passwordHash.split(":");
			if (parts.length != 3 || !"sha256".equals(parts[0])) {
				return false;
			}
			try {
				byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
						.digest((parts[1] + candidate).getBytes(java.nio.charset.StandardCharsets.UTF_8));
				StringBuilder hex = new StringBuilder();
				for (byte b : digest) {
					hex.append(String.format("%02x", b));
				}
				return java.security.MessageDigest.isEqual(hex.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
						parts[2].getBytes(java.nio.charset.StandardCharsets.US_ASCII));
			} catch (java.security.NoSuchAlgorithmException e) {
				throw new IllegalStateException(e);
			}
		}

		/**
		 * Returns the display name (token {@code name} claim).
		 *
		 * @return name
		 */
		public String getName() {
			return name;
		}

		/**
		 * Sets the display name.
		 *
		 * @param name name
		 */
		public void setName(String name) {
			this.name = name;
		}

		/**
		 * Returns the roles (token {@code realm_access.roles}).
		 *
		 * @return roles
		 */
		public List<String> getRoles() {
			return roles;
		}

		/**
		 * Sets the roles.
		 *
		 * @param roles roles
		 */
		public void setRoles(List<String> roles) {
			this.roles = roles;
		}

		/**
		 * Tells whether the account is active.
		 *
		 * @return active flag
		 */
		public boolean isActive() {
			return active;
		}

		/**
		 * Sets the active flag.
		 *
		 * @param active active flag
		 */
		public void setActive(boolean active) {
			this.active = active;
		}
	}
}
