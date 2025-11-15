use fory_core::{
    fory::{get_default_config, set_default_config, Config},
    Fory,
};

#[test]
fn test_config() {
    let default_cfg = get_default_config();
    let compatible = true;
    let new_cfg = Config {
        compatible,
        ..default_cfg
    };
    set_default_config(new_cfg);
    let fory = Fory::default();
    assert_eq!(fory.is_compatible(), compatible);
}
