package io.mosip.mocksync.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;

/**
 * Operator accounts from {@code data/users.json}. The passwords are synthetic test values documented in the README.
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
		private String password;
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
		 * Returns the password.
		 *
		 * @return password
		 */
		public String getPassword() {
			return password;
		}

		/**
		 * Sets the password.
		 *
		 * @param password password
		 */
		public void setPassword(String password) {
			this.password = password;
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
