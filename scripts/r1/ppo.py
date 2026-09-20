import numpy as np

from .core import R1PilotEnvironment


def collect_rollouts(model, scenes, rng, deterministic=False):
    transitions = []
    episode_summaries = []
    for scene in scenes:
        env = R1PilotEnvironment(scene)
        episode = []
        selected_actions = []
        while not env.done:
            state, snapshot = env.observe()
            index, old_log_prob, value = model.choose(state, snapshot, rng, deterministic)
            _, reward, done, _ = env.step(snapshot.action_ids[index], snapshot)
            selected_actions.append(index)
            episode.append({
                "state": state.copy(),
                "features": snapshot.features.copy(),
                "mask": snapshot.mask.copy(),
                "action": index,
                "old_log_prob": old_log_prob,
                "value": value,
                "reward": reward,
                "done": done,
                "candidate_hash": snapshot.candidate_hash,
                "action_ids": snapshot.action_ids,
            })
        running_return = 0.0
        for transition in reversed(episode):
            running_return = transition["reward"] + running_return
            transition["return"] = running_return
            transition["advantage"] = running_return - transition["value"]
        transitions.extend(episode)
        episode_summaries.append({
            "scene_id": scene["scene_id"],
            "initial_score": env.initial_score,
            "best_score": env.best_score,
            "best_action": env.best_action,
            "reward_sum": sum(item["reward"] for item in episode),
            "selected_actions": selected_actions,
            "policy_calls": len(episode),
        })
    return transitions, episode_summaries


def ppo_update(model, transitions, learning_rate=3e-4, clip_epsilon=0.2,
               update_epochs=4, value_coefficient=0.5, entropy_coefficient=0.01):
    if not transitions:
        return {"actor_loss": 0.0, "critic_loss": 0.0, "entropy": 0.0}
    advantages = np.asarray([item["advantage"] for item in transitions])
    advantages = (advantages - advantages.mean()) / max(advantages.std(), 1.0e-8)
    stats = []
    for _ in range(update_epochs):
        actor_loss = 0.0
        critic_loss = 0.0
        entropy_total = 0.0
        grad_interaction = np.zeros_like(model.interaction)
        grad_action_bias = np.zeros_like(model.action_bias)
        grad_critic = np.zeros_like(model.critic)
        grad_critic_bias = 0.0

        for item, advantage in zip(transitions, advantages):
            state = item["state"]
            features = item["features"]
            mask = item["mask"]
            _, probabilities, value = model.forward(state, features, mask)
            action = item["action"]
            log_probability = np.log(max(probabilities[action], 1.0e-12))
            ratio = float(np.exp(log_probability - item["old_log_prob"]))
            clipped_ratio = float(np.clip(ratio, 1.0 - clip_epsilon, 1.0 + clip_epsilon))
            actor_loss -= min(ratio * advantage, clipped_ratio * advantage)

            gradient_active = (advantage >= 0 and ratio <= 1.0 + clip_epsilon) or (
                advantage < 0 and ratio >= 1.0 - clip_epsilon
            )
            dlogits = np.zeros_like(probabilities)
            if gradient_active:
                one_hot = np.zeros_like(probabilities)
                one_hot[action] = 1.0
                dlogits += -advantage * ratio * (one_hot - probabilities)

            legal = np.flatnonzero(mask)
            legal_probabilities = probabilities[legal]
            entropy = -float(np.sum(legal_probabilities * np.log(np.maximum(legal_probabilities, 1.0e-12))))
            entropy_total += entropy
            entropy_helper = np.sum(legal_probabilities * (
                np.log(np.maximum(legal_probabilities, 1.0e-12)) + 1.0
            ))
            entropy_gradient = np.zeros_like(probabilities)
            entropy_gradient[legal] = legal_probabilities * (
                entropy_helper - np.log(np.maximum(legal_probabilities, 1.0e-12)) - 1.0
            )
            dlogits -= entropy_coefficient * entropy_gradient

            for index in legal:
                grad_action_bias += dlogits[index] * features[index]
                grad_interaction += dlogits[index] * np.outer(state, features[index])

            value_error = value - item["return"]
            critic_loss += value_error * value_error
            grad_critic += 2.0 * value_coefficient * value_error * state
            grad_critic_bias += 2.0 * value_coefficient * value_error

        scale = 1.0 / len(transitions)
        model.interaction -= learning_rate * grad_interaction * scale
        model.action_bias -= learning_rate * grad_action_bias * scale
        model.critic -= learning_rate * grad_critic * scale
        model.critic_bias -= learning_rate * grad_critic_bias * scale
        stats.append({
            "actor_loss": actor_loss * scale,
            "critic_loss": critic_loss * scale,
            "entropy": entropy_total * scale,
        })
    return stats[-1]
