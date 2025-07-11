use fory_core::{fory::Fory, types::Mode};
use fory_derive::Fory;


// cargo expand -p fory-tests --test my_expand

#[test]
fn my_expand() {
    #[derive(Fory, Debug)]
    struct Animal {
        f3: String,
    }
    let mut fory = Fory::default().mode(Mode::Compatible);
    fory.register::<Animal>(999);
    let animal = Animal {
        f3: String::from("hello"),
    };
    let bin = fory.serialize(&animal);

    
    
}